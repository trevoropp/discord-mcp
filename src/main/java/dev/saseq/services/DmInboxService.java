package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Keeps an inbox of direct messages sent to the bot.
 *
 * Discord does not let a bot list its DM conversations or poll for new DMs, so without this
 * the only way to read a DM was {@code read_private_messages}, which needs the sender's user ID
 * up front. A DM from someone the agent had not already messaged was invisible to it.
 *
 * This service listens for every DM the bot receives, stores it in memory, and exposes it through
 * {@code check_dm_inbox}.
 *
 * Optionally it also forwards messages to an HTTP endpoint the moment they arrive, so an agent can
 * be triggered instead of polling. Each server has its own endpoint: set {@code WEBHOOK_URL_<guildId>}
 * (and {@code WEBHOOK_SECRET_<guildId>}, sent in the header named by {@code WEBHOOK_SECRET_HEADER},
 * default {@code X-Webhook-Secret}). A server message that mentions the bot, or lands in a channel
 * listed in {@code WEBHOOK_CHANNEL_IDS}, goes to that server's endpoint as
 * {@code "type":"channel_message"}. A DM ({@code "type":"dm_received"}) goes to exactly one server's
 * endpoint: the one named for that user in {@code WEBHOOK_DM_OVERRIDES} ({@code userId:guildId,...}),
 * else the first server in {@code WEBHOOK_DM_PRIORITY} (comma-separated guild IDs) the user is a
 * member of. The payload carries {@code routedGuildId}/{@code routedGuildName} and
 * {@code memberGuildIds} (every routed server the sender belongs to) so an agent can hand off a
 * message that belongs to another business. Edits to any forwarded message are sent as
 * {@code "type":"message_edited"} with the new content.
 * A delivery that fails with a network error or a 5xx is retried once after a short delay; a 4xx
 * is not retried, but the response body is logged so the rejection can be diagnosed.
 *
 * Bursts are coalesced: the first message from a sender starts a hold of
 * {@code WEBHOOK_COALESCE_SECONDS} (default 45, 0 disables), and anything more they send in that
 * window, including edits to held messages, goes out in the same push with {@code content} joined
 * by newlines and {@code messageIds}/{@code messageCount} listing what it covers. Messages that
 * @mention the bot skip the hold. Held messages are flushed on shutdown.
 *
 * The inbox holds at most {@link #MAX_INBOX_SIZE} messages (oldest dropped first). Set
 * {@code INBOX_FILE} to a path on persistent storage (e.g. a mounted volume) and the inbox is
 * written there on every change and reloaded on startup, so a restart or redeploy loses nothing.
 * Without it the inbox is memory-only and empty after each restart.
 */
@Service
public class DmInboxService extends ListenerAdapter implements DisposableBean {

    /** About two weeks of DMs at a few hundred per day. */
    private static final int MAX_INBOX_SIZE = 3000;
    private static final Duration WEBHOOK_RETRY_DELAY = Duration.ofSeconds(5);

    private final JDA jda;
    private final Deque<InboxEntry> inbox = new ArrayDeque<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** One server's agent endpoint. */
    private record Route(String guildId, String guildName, String url, String secret) {}

    private final String webhookSecretHeader;
    /** guild ID -> that server's webhook. Servers without a configured URL are not pushed anywhere. */
    private final Map<String, Route> routes;
    /** Guild IDs in the order used to pick one agent for a DM from someone in several servers. */
    private final List<String> dmPriority;
    /** user ID -> guild ID: force that person's DMs to a particular server's agent. */
    private final Map<String, String> dmOverrides;
    private final Set<String> forwardedChannelIds;
    private final Path inboxFile;
    private final long coalesceMillis;
    private final Map<String, Batch> pending = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-coalesce");
        t.setDaemon(true);
        return t;
    });

    public DmInboxService(JDA jda,
                          Environment env,
                          @Value("${WEBHOOK_SECRET_HEADER:X-Webhook-Secret}") String webhookSecretHeader,
                          @Value("${WEBHOOK_CHANNEL_IDS:}") String webhookChannelIds,
                          @Value("${WEBHOOK_DM_PRIORITY:}") String dmPriority,
                          @Value("${WEBHOOK_DM_OVERRIDES:}") String dmOverrides,
                          @Value("${WEBHOOK_COALESCE_SECONDS:45}") String coalesceSeconds,
                          @Value("${INBOX_FILE:}") String inboxFilePath) {
        this.jda = jda;
        this.inboxFile = (inboxFilePath == null || inboxFilePath.isBlank()) ? null : Path.of(inboxFilePath.trim());
        loadInbox();
        long coalesce;
        try {
            coalesce = Math.round(Double.parseDouble(coalesceSeconds.trim()) * 1000);
        } catch (Exception ex) {
            System.err.println("WEBHOOK_COALESCE_SECONDS '" + coalesceSeconds + "' is not a number; using 45");
            coalesce = 45_000;
        }
        this.coalesceMillis = Math.max(0, coalesce);
        this.webhookSecretHeader = (webhookSecretHeader == null || webhookSecretHeader.isBlank())
                ? "X-Webhook-Secret" : webhookSecretHeader.trim();
        this.forwardedChannelIds = splitIds(webhookChannelIds).stream().collect(Collectors.toUnmodifiableSet());

        // One webhook per server: WEBHOOK_URL_<guildId> and WEBHOOK_SECRET_<guildId>.
        Map<String, Route> found = new LinkedHashMap<>();
        for (Guild guild : jda.getGuilds()) {
            String url = env.getProperty("WEBHOOK_URL_" + guild.getId(), "").trim();
            if (url.isEmpty()) {
                continue;
            }
            String secret = env.getProperty("WEBHOOK_SECRET_" + guild.getId(), "").trim();
            found.put(guild.getId(), new Route(guild.getId(), guild.getName(), url, secret));
        }
        this.routes = Map.copyOf(found);

        List<String> priority = new ArrayList<>(splitIds(dmPriority));
        priority.removeIf(id -> !routes.containsKey(id));
        for (String id : found.keySet()) { // any routed server not listed goes last, in server order
            if (!priority.contains(id)) {
                priority.add(id);
            }
        }
        this.dmPriority = List.copyOf(priority);

        Map<String, String> overrides = new HashMap<>();
        for (String pair : splitIds(dmOverrides)) {
            String[] parts = pair.split(":");
            if (parts.length == 2 && routes.containsKey(parts[1].trim())) {
                overrides.put(parts[0].trim(), parts[1].trim());
            } else {
                System.err.println("WEBHOOK_DM_OVERRIDES: ignoring '" + pair + "' (expected userId:guildId of a routed server)");
            }
        }
        this.dmOverrides = Map.copyOf(overrides);

        if (routes.isEmpty()) {
            System.out.println("Message webhook disabled: no WEBHOOK_URL_<guildId> is set");
        } else {
            System.out.println("Message webhook enabled for " + routes.size() + " server(s): "
                    + routes.values().stream().map(Route::guildName).collect(Collectors.joining(", "))
                    + "; forwarding DMs, mentions of the bot, and " + forwardedChannelIds.size() + " watched channel(s)"
                    + "; DM priority " + this.dmPriority.stream().map(id -> routes.get(id).guildName()).collect(Collectors.joining(" > "))
                    + (this.dmOverrides.isEmpty() ? "" : "; " + this.dmOverrides.size() + " DM override(s)")
                    + (coalesceMillis > 0 ? "; coalescing bursts over " + (coalesceMillis / 1000.0) + "s" : "; no coalescing"));
            for (Guild guild : jda.getGuilds()) {
                if (!routes.containsKey(guild.getId())) {
                    System.out.println("Message webhook: no WEBHOOK_URL_" + guild.getId() + " for server '" + guild.getName() + "', its messages are not pushed");
                }
            }
        }
        jda.addEventListener(this);
    }

    private static List<String> splitIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Every routed server this user is a member of, in DM priority order. */
    private List<String> memberGuildIds(User user) {
        List<String> ids = new ArrayList<>();
        for (String guildId : dmPriority) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null && guild.getMember(user) != null) {
                ids.add(guildId);
            }
        }
        return ids;
    }

    /** The server whose agent owns this user's DMs: an explicit override, else the first routed server they belong to. */
    private String dmRouteFor(User user, List<String> memberGuildIds) {
        String override = dmOverrides.get(user.getId());
        if (override != null) {
            return override;
        }
        return memberGuildIds.isEmpty() ? null : memberGuildIds.get(0);
    }

    private record InboxEntry(String messageId,
                              String userId,
                              String username,
                              String displayName,
                              OffsetDateTime receivedAt,
                              String content,
                              List<String> attachmentUrls,
                              boolean unread) {
        InboxEntry markRead() {
            return new InboxEntry(messageId, userId, username, displayName, receivedAt, content, attachmentUrls, false);
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Message message = event.getMessage();
        User author = message.getAuthor();
        if (author.isBot() || author.getIdLong() == jda.getSelfUser().getIdLong()) {
            return;
        }
        InboxEntry entry = toEntry(message);

        if (message.getChannelType() == ChannelType.PRIVATE) {
            synchronized (inbox) {
                inbox.addLast(entry);
                while (inbox.size() > MAX_INBOX_SIZE) {
                    inbox.removeFirst();
                }
                saveInbox();
            }
            queueDm("dm_received", author, entry);
            return;
        }

        if (!message.isFromGuild()) {
            return;
        }
        if (shouldForwardGuildMessage(message)) {
            queueForWebhook("channel_message", entry, message.getGuild().getId(), memberGuildIds(author),
                    message.getChannel().getId(), message.getChannel().getName(), mentionsBot(message));
        }
    }

    /** Routes a DM to the one server agent that owns this person's DMs, if any. */
    private void queueDm(String type, User author, InboxEntry entry) {
        List<String> memberOf = memberGuildIds(author);
        String routeGuildId = dmRouteFor(author, memberOf);
        if (routeGuildId == null) {
            if (!routes.isEmpty()) {
                System.out.println("DM " + entry.messageId() + " from " + author.getName()
                        + " not pushed: not a member of any routed server (still in the inbox)");
            }
            return;
        }
        queueForWebhook(type, entry, routeGuildId, memberOf, null, null, false);
    }

    /**
     * An edit to a message that would have been forwarded when it was sent is forwarded again as
     * {@code message_edited}, carrying the new content, so the agent is not left acting on text the
     * sender has since changed. Edited DMs also replace their original entry in the inbox.
     */
    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        Message message = event.getMessage();
        User author = message.getAuthor();
        if (author.isBot() || author.getIdLong() == jda.getSelfUser().getIdLong()) {
            return;
        }
        InboxEntry edited = toEntry(message);

        if (message.getChannelType() == ChannelType.PRIVATE) {
            synchronized (inbox) {
                List<InboxEntry> updated = new ArrayList<>(inbox.size());
                boolean replaced = false;
                for (InboxEntry e : inbox) {
                    if (e.messageId().equals(edited.messageId())) {
                        // keep the original received time and read state, swap in the new content
                        updated.add(new InboxEntry(e.messageId(), e.userId(), e.username(), e.displayName(),
                                e.receivedAt(), edited.content(), edited.attachmentUrls(), e.unread()));
                        replaced = true;
                    } else {
                        updated.add(e);
                    }
                }
                if (replaced) {
                    inbox.clear();
                    inbox.addAll(updated);
                    saveInbox();
                }
            }
            // Edited while the original is still held: the hold picks up the new text, no extra push.
            if (!updatePendingIfHeld("dm_received", edited, null)) {
                queueDm("message_edited", author, edited);
            }
            return;
        }

        if (!message.isFromGuild()) {
            return;
        }
        if (shouldForwardGuildMessage(message)) {
            String channelId = message.getChannel().getId();
            if (!updatePendingIfHeld("channel_message", edited, channelId)) {
                queueForWebhook("message_edited", edited, message.getGuild().getId(), memberGuildIds(author),
                        channelId, message.getChannel().getName(), false);
            }
        }
    }

    private InboxEntry toEntry(Message message) {
        User author = message.getAuthor();
        return new InboxEntry(
                message.getId(),
                author.getId(),
                author.getName(),
                author.getGlobalName() != null ? author.getGlobalName() : author.getName(),
                message.getTimeCreated(),
                message.getContentRaw(),
                message.getAttachments().stream().map(Message.Attachment::getUrl).toList(),
                true
        );
    }

    private boolean shouldForwardGuildMessage(Message message) {
        return forwardedChannelIds.contains(message.getChannel().getId()) || mentionsBot(message);
    }

    private boolean mentionsBot(Message message) {
        return message.getMentions().isMentioned(jda.getSelfUser(), Message.MentionType.USER);
    }

    /**
     * Lists DMs the bot has received. By default returns only unread ones and marks them read.
     */
    @Tool(name = "check_dm_inbox", description = "List direct messages sent to the bot. Returns unread DMs by default and marks them as read, so each call shows what is new since the last check. Use this to find out who has messaged the bot, then reply with send_private_message using the sender's user ID. The inbox is saved to disk and survives bot restarts and redeploys; it keeps the most recent " + MAX_INBOX_SIZE + " DMs (roughly two weeks), oldest dropped first.")
    public String checkDmInbox(@ToolParam(description = "true (default) = only DMs not yet returned by a previous call; false = every DM kept in the inbox", required = false) String unreadOnly,
                               @ToolParam(description = "true (default) = mark the returned DMs as read; false = leave them unread so the next call returns them again", required = false) String markAsRead,
                               @ToolParam(description = "Only return DMs from this user ID", required = false) String fromUserId) {
        boolean onlyUnread = !"false".equalsIgnoreCase(unreadOnly);
        boolean mark = !"false".equalsIgnoreCase(markAsRead);

        List<InboxEntry> selected = new ArrayList<>();
        synchronized (inbox) {
            List<InboxEntry> updated = new ArrayList<>(inbox.size());
            for (InboxEntry entry : inbox) {
                boolean matches = (!onlyUnread || entry.unread())
                        && (fromUserId == null || fromUserId.isBlank() || fromUserId.equals(entry.userId()));
                if (matches) {
                    selected.add(entry);
                    updated.add(mark ? entry.markRead() : entry);
                } else {
                    updated.add(entry);
                }
            }
            inbox.clear();
            inbox.addAll(updated);
            if (mark && !selected.isEmpty()) {
                saveInbox();
            }
        }

        if (selected.isEmpty()) {
            return onlyUnread ? "No unread DMs." : "No DMs in the inbox.";
        }
        String body = selected.stream().map(this::format).collect(Collectors.joining("\n"));
        return "**" + selected.size() + (onlyUnread ? " unread" : "") + " DM(s):**\n" + body;
    }

    /**
     * Lists the users the bot currently has a DM conversation open with.
     */
    @Tool(name = "list_dm_conversations", description = "List users the bot has an open DM conversation with, newest activity first. Only conversations seen since the bot last started are included.")
    public String listDmConversations() {
        List<PrivateChannel> channels = jda.getPrivateChannels().stream()
                .filter(c -> c.getUser() != null)
                .sorted(Comparator.comparingLong(PrivateChannel::getLatestMessageIdLong).reversed())
                .toList();
        if (channels.isEmpty()) {
            return "No DM conversations known since the bot started.";
        }
        String body = channels.stream()
                .map(c -> {
                    User u = c.getUser();
                    return String.format("- user ID: %s | username: %s | display name: %s",
                            u.getId(), u.getName(), u.getGlobalName() != null ? u.getGlobalName() : "(none)");
                })
                .collect(Collectors.joining("\n"));
        return "**" + channels.size() + " DM conversation(s):**\n" + body;
    }

    private String format(InboxEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("- (ID: %s) **[%s | user ID: %s | display name: %s]** `%s`%s: ```%s```",
                e.messageId(), e.username(), e.userId(), e.displayName(), e.receivedAt(),
                e.unread() ? " (unread)" : "", e.content()));
        if (!e.attachmentUrls().isEmpty()) {
            sb.append("\n  Attachments:");
            for (String url : e.attachmentUrls()) {
                sb.append("\n    - ").append(url);
            }
        }
        return sb.toString();
    }

    // ---- webhook delivery, with per-sender coalescing ----

    /** Messages from one sender (per channel) waiting to be pushed as a single webhook call. */
    private static final class Batch {
        final String type;
        final Route route;
        /** For channel messages, the server the channel is in; for DMs, the server whose agent was chosen. */
        final String guildId;
        final List<String> memberGuildIds;
        final String channelId;
        final String channelName;
        final List<InboxEntry> entries = new ArrayList<>();

        Batch(String type, Route route, String guildId, List<String> memberGuildIds, String channelId, String channelName) {
            this.type = type;
            this.route = route;
            this.guildId = guildId;
            this.memberGuildIds = memberGuildIds;
            this.channelId = channelId;
            this.channelName = channelName;
        }
    }

    /**
     * Queues a message for the webhook of the server {@code guildId}. With coalescing on, the first
     * message from a sender starts a short hold; anything more they send in that window (or an edit
     * to a held message) joins the same push, so a thought typed as several messages reaches the
     * agent as one event. Messages that are {@code immediate} (the bot being @mentioned) skip the hold.
     */
    private void queueForWebhook(String type, InboxEntry e, String guildId, List<String> memberGuildIds,
                                 String channelId, String channelName, boolean immediate) {
        Route route = routes.get(guildId);
        if (route == null) {
            return; // server has no agent webhook configured
        }
        if (coalesceMillis <= 0 || immediate) {
            forwardToWebhook(new Batch(type, route, guildId, memberGuildIds, channelId, channelName), List.of(e), false);
            return;
        }
        String key = type + ":" + e.userId() + ":" + (channelId == null ? "dm" : channelId);
        synchronized (pending) {
            Batch batch = pending.get(key);
            if (batch == null) {
                batch = new Batch(type, route, guildId, memberGuildIds, channelId, channelName);
                pending.put(key, batch);
                scheduler.schedule(() -> flush(key), coalesceMillis, TimeUnit.MILLISECONDS);
            }
            boolean replaced = false;
            for (int i = 0; i < batch.entries.size(); i++) {
                if (batch.entries.get(i).messageId().equals(e.messageId())) {
                    batch.entries.set(i, e); // edited while held: push the final text only
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                batch.entries.add(e);
            }
        }
    }

    /** True if the sender still has this message waiting in a hold, in which case an edit just updates it. */
    private boolean updatePendingIfHeld(String type, InboxEntry edited, String channelId) {
        String key = type + ":" + edited.userId() + ":" + (channelId == null ? "dm" : channelId);
        synchronized (pending) {
            Batch batch = pending.get(key);
            if (batch == null) {
                return false;
            }
            for (int i = 0; i < batch.entries.size(); i++) {
                if (batch.entries.get(i).messageId().equals(edited.messageId())) {
                    batch.entries.set(i, edited);
                    return true;
                }
            }
            return false;
        }
    }

    private void flush(String key) {
        Batch batch;
        synchronized (pending) {
            batch = pending.remove(key);
        }
        if (batch != null && !batch.entries.isEmpty()) {
            forwardToWebhook(batch, batch.entries, false);
        }
    }

    /** Pushes whatever is still held, synchronously, so a shutdown or redeploy does not drop a batch. */
    @Override
    public void destroy() {
        List<Batch> remaining;
        synchronized (pending) {
            remaining = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (Batch batch : remaining) {
            if (!batch.entries.isEmpty()) {
                forwardToWebhook(batch, batch.entries, true);
            }
        }
        scheduler.shutdownNow();
    }

    /**
     * Builds and sends one webhook call for one or more messages from the same sender. The payload
     * keeps the single-message fields (messageId and receivedAt are the first message's, content is
     * all messages joined by newlines) and adds messageIds and messageCount.
     */
    private void forwardToWebhook(Batch batch, List<InboxEntry> entries, boolean sync) {
        InboxEntry first = entries.get(0);
        String content = entries.stream().map(InboxEntry::content).collect(Collectors.joining("\n"));
        List<String> attachments = entries.stream().flatMap(x -> x.attachmentUrls().stream()).toList();
        boolean dm = batch.channelId == null;
        String json = "{"
                + "\"type\":\"" + batch.type + "\","
                // guildId stays null for DMs (they have no server); routedGuildId says whose agent received it
                + "\"guildId\":" + jsonOrNull(dm ? null : batch.guildId) + ","
                + "\"routedGuildId\":\"" + escape(batch.guildId) + "\","
                + "\"routedGuildName\":\"" + escape(batch.route.guildName()) + "\","
                + "\"memberGuildIds\":[" + batch.memberGuildIds.stream()
                        .map(x -> "\"" + escape(x) + "\"").collect(Collectors.joining(",")) + "],"
                + "\"channelId\":" + jsonOrNull(batch.channelId) + ","
                + "\"channelName\":" + jsonOrNull(batch.channelName) + ","
                + "\"messageId\":\"" + escape(first.messageId()) + "\","
                + "\"messageIds\":[" + entries.stream()
                        .map(x -> "\"" + escape(x.messageId()) + "\"").collect(Collectors.joining(",")) + "],"
                + "\"messageCount\":" + entries.size() + ","
                + "\"userId\":\"" + escape(first.userId()) + "\","
                + "\"username\":\"" + escape(first.username()) + "\","
                + "\"displayName\":\"" + escape(first.displayName()) + "\","
                + "\"receivedAt\":\"" + escape(first.receivedAt().toString()) + "\","
                + "\"content\":\"" + escape(content) + "\","
                + "\"attachments\":[" + attachments.stream()
                        .map(u -> "\"" + escape(u) + "\"").collect(Collectors.joining(",")) + "]"
                + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(batch.route.url()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (!batch.route.secret().isEmpty()) {
            request.header(webhookSecretHeader, batch.route.secret());
        }
        String label = batch.type + " " + first.messageId() + (entries.size() > 1 ? " (+" + (entries.size() - 1) + ")" : "")
                + " -> " + batch.route.guildName();
        if (sync) {
            try {
                HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    System.err.println("Message webhook returned HTTP " + response.statusCode() + " for " + label + " during shutdown flush");
                }
            } catch (Exception ex) {
                System.err.println("Message webhook delivery failed for " + label + " during shutdown flush: " + ex.getMessage());
            }
            return;
        }
        sendWebhook(request.build(), label, 1);
    }

    /**
     * Sends one webhook request. A network failure or a 5xx is retried once after
     * {@link #WEBHOOK_RETRY_DELAY}; a 4xx means the receiver rejected the payload, so it is
     * logged with the response body and not retried.
     */
    private void sendWebhook(HttpRequest request, String label, int attempt) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status < 300) {
                        return;
                    }
                    if (status >= 500 && attempt == 1) {
                        System.err.println("Message webhook returned HTTP " + status + " for " + label
                                + "; retrying once");
                        retryWebhook(request, label);
                        return;
                    }
                    String body = response.body() == null ? "" : response.body().strip();
                    if (body.length() > 500) {
                        body = body.substring(0, 500) + "...";
                    }
                    System.err.println("Message webhook returned HTTP " + status + " for " + label
                            + (attempt > 1 ? " (after retry)" : "")
                            + (body.isEmpty() ? "" : ": " + body));
                })
                .exceptionally(ex -> {
                    if (attempt == 1) {
                        System.err.println("Message webhook delivery failed for " + label + ": "
                                + ex.getMessage() + "; retrying once");
                        retryWebhook(request, label);
                    } else {
                        System.err.println("Message webhook delivery failed for " + label
                                + " after retry: " + ex.getMessage());
                    }
                    return null;
                });
    }

    private void retryWebhook(HttpRequest request, String label) {
        CompletableFuture.delayedExecutor(WEBHOOK_RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> sendWebhook(request, label, 2));
    }

    // ---- persistence (one entry per line, fields tab-separated, free text base64-encoded) ----

    private static final String FILE_HEADER = "# discord-mcp dm inbox v1";

    /** Must be called with the inbox lock held. Failures are logged, never thrown: the inbox keeps working in memory. */
    private void saveInbox() {
        if (inboxFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>(inbox.size() + 1);
            lines.add(FILE_HEADER);
            for (InboxEntry e : inbox) {
                lines.add(String.join("\t",
                        e.messageId(),
                        e.userId(),
                        b64(e.username()),
                        b64(e.displayName()),
                        e.receivedAt().toString(),
                        e.unread() ? "1" : "0",
                        b64(e.content()),
                        e.attachmentUrls().stream().map(DmInboxService::b64).collect(Collectors.joining(","))));
            }
            Path parent = inboxFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = inboxFile.resolveSibling(inboxFile.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, inboxFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            System.err.println("Could not save DM inbox to " + inboxFile + ": " + ex.getMessage());
        }
    }

    private void loadInbox() {
        if (inboxFile == null || !Files.exists(inboxFile)) {
            return;
        }
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(inboxFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length < 8) {
                    continue;
                }
                List<String> attachments = f[7].isEmpty()
                        ? List.of()
                        : Arrays.stream(f[7].split(",")).map(DmInboxService::unb64).toList();
                synchronized (inbox) {
                    inbox.addLast(new InboxEntry(f[0], f[1], unb64(f[2]), unb64(f[3]),
                            OffsetDateTime.parse(f[4]), unb64(f[6]), attachments, "1".equals(f[5])));
                    while (inbox.size() > MAX_INBOX_SIZE) {
                        inbox.removeFirst();
                    }
                }
                loaded++;
            }
            System.out.println("Loaded " + loaded + " DM(s) into the inbox from " + inboxFile);
        } catch (Exception ex) {
            System.err.println("Could not load DM inbox from " + inboxFile + ": " + ex.getMessage());
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static String jsonOrNull(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
