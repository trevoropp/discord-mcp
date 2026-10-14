package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
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
 * be triggered instead of polling. Set {@code WEBHOOK_URL} to turn this on. What gets forwarded:
 * every DM ({@code "type":"dm_received"}), every server message that mentions the bot, and every
 * message in a channel listed in {@code WEBHOOK_CHANNEL_IDS} (comma-separated channel IDs); the
 * last two are sent as {@code "type":"channel_message"}. {@code WEBHOOK_SECRET} is sent on each
 * request in the header named by {@code WEBHOOK_SECRET_HEADER} (default {@code X-Webhook-Secret}).
 *
 * The inbox lives in memory only: it is empty after a restart, and holds at most
 * {@link #MAX_INBOX_SIZE} messages (oldest dropped first).
 */
@Service
public class DmInboxService extends ListenerAdapter {

    private static final int MAX_INBOX_SIZE = 500;

    private final JDA jda;
    private final Deque<InboxEntry> inbox = new ArrayDeque<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String webhookUrl;
    private final String webhookSecret;
    private final String webhookSecretHeader;
    private final Set<String> forwardedChannelIds;

    public DmInboxService(JDA jda,
                          @Value("${WEBHOOK_URL:}") String webhookUrl,
                          @Value("${WEBHOOK_SECRET:}") String webhookSecret,
                          @Value("${WEBHOOK_SECRET_HEADER:X-Webhook-Secret}") String webhookSecretHeader,
                          @Value("${WEBHOOK_CHANNEL_IDS:}") String webhookChannelIds) {
        this.jda = jda;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.webhookSecretHeader = (webhookSecretHeader == null || webhookSecretHeader.isBlank())
                ? "X-Webhook-Secret" : webhookSecretHeader.trim();
        this.forwardedChannelIds = (webhookChannelIds == null || webhookChannelIds.isBlank())
                ? Set.of()
                : Arrays.stream(webhookChannelIds.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        if (!this.webhookUrl.isEmpty()) {
            System.out.println("Message webhook enabled: forwarding DMs, mentions of the bot, and "
                    + forwardedChannelIds.size() + " watched channel(s)");
        }
        jda.addEventListener(this);
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
        User author = event.getAuthor();
        if (author.isBot() || author.getIdLong() == jda.getSelfUser().getIdLong()) {
            return;
        }
        Message message = event.getMessage();
        InboxEntry entry = new InboxEntry(
                message.getId(),
                author.getId(),
                author.getName(),
                author.getGlobalName() != null ? author.getGlobalName() : author.getName(),
                message.getTimeCreated(),
                message.getContentRaw(),
                message.getAttachments().stream().map(Message.Attachment::getUrl).toList(),
                true
        );

        if (event.getChannelType() == ChannelType.PRIVATE) {
            synchronized (inbox) {
                inbox.addLast(entry);
                while (inbox.size() > MAX_INBOX_SIZE) {
                    inbox.removeFirst();
                }
            }
            forwardToWebhook("dm_received", entry, null, null, null);
            return;
        }

        if (!event.isFromGuild()) {
            return;
        }
        boolean watchedChannel = forwardedChannelIds.contains(event.getChannel().getId());
        boolean mentionsBot = message.getMentions().isMentioned(jda.getSelfUser(), Message.MentionType.USER);
        if (watchedChannel || mentionsBot) {
            forwardToWebhook("channel_message", entry,
                    event.getGuild().getId(), event.getChannel().getId(), event.getChannel().getName());
        }
    }

    /**
     * Lists DMs the bot has received since it started. By default returns only unread ones and marks them read.
     */
    @Tool(name = "check_dm_inbox", description = "List direct messages sent to the bot. Returns unread DMs by default and marks them as read, so each call shows what is new since the last check. Use this to find out who has messaged the bot, then reply with send_private_message using the sender's user ID. The inbox is kept in memory since the bot last started.")
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

    private void forwardToWebhook(String type, InboxEntry e, String guildId, String channelId, String channelName) {
        if (webhookUrl.isEmpty()) {
            return;
        }
        String json = "{"
                + "\"type\":\"" + type + "\","
                + "\"guildId\":" + jsonOrNull(guildId) + ","
                + "\"channelId\":" + jsonOrNull(channelId) + ","
                + "\"channelName\":" + jsonOrNull(channelName) + ","
                + "\"messageId\":\"" + escape(e.messageId()) + "\","
                + "\"userId\":\"" + escape(e.userId()) + "\","
                + "\"username\":\"" + escape(e.username()) + "\","
                + "\"displayName\":\"" + escape(e.displayName()) + "\","
                + "\"receivedAt\":\"" + escape(e.receivedAt().toString()) + "\","
                + "\"content\":\"" + escape(e.content()) + "\","
                + "\"attachments\":[" + e.attachmentUrls().stream()
                        .map(u -> "\"" + escape(u) + "\"").collect(Collectors.joining(",")) + "]"
                + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (!webhookSecret.isEmpty()) {
            request.header(webhookSecretHeader, webhookSecret);
        }
        httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        System.err.println("Message webhook returned HTTP " + response.statusCode()
                                + " for " + type + " " + e.messageId());
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("Message webhook delivery failed for " + type + " " + e.messageId()
                            + ": " + ex.getMessage());
                    return null;
                });
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
