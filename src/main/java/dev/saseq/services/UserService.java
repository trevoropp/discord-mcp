package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final JDA jda;
    private final CompactFormatter compact;
    private final DmInboxService dmInbox;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public UserService(JDA jda, CompactFormatter compact, DmInboxService dmInbox) {
        this.jda = jda;
        this.compact = compact;
        this.dmInbox = dmInbox;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild requireGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    /**
     * Returns every member of the guild. Asks Discord for the full list; falls back to the local cache on failure.
     */
    private List<Member> loadAllMembers(Guild guild) {
        try {
            return guild.loadMembers().get();
        } catch (Exception ex) {
            return guild.getMembers();
        }
    }

    static String describeMember(Member m) {
        User u = m.getUser();
        String nickname = m.getNickname();
        String globalName = u.getGlobalName();
        return String.format("ID: %s | mention: <@%s> | username: %s | display name: %s | server nickname: %s%s",
                u.getId(), u.getId(), u.getName(),
                globalName != null ? globalName : "(none)",
                nickname != null ? nickname : "(none)",
                u.isBot() ? " | BOT" : "");
    }

    private static boolean nameMatches(Member m, String query, boolean exact) {
        String q = query.toLowerCase();
        for (String candidate : new String[]{m.getUser().getName(), m.getUser().getGlobalName(), m.getNickname()}) {
            if (candidate == null) continue;
            String c = candidate.toLowerCase();
            if (exact ? c.equals(q) : c.contains(q)) return true;
        }
        return false;
    }

    /**
     * Finds members by username, display name or server nickname and returns their IDs and names.
     */
    @Tool(name = "get_user_id_by_name", description = "Find a Discord member by username, display name, or server nickname (case-insensitive; partial matches allowed). Returns user ID, username, display name and server nickname for each match. Ping a user with <@id>.")
    public String getUserIdByName(
            @ToolParam(description = "Username, display name, or server nickname (full or partial)") String username,
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username cannot be null");
        }
        Guild guild = requireGuild(guildId);
        String query = username.trim();
        if (query.startsWith("@")) {
            query = query.substring(1);
        }
        int hash = query.lastIndexOf('#');
        if (hash > 0 && query.substring(hash + 1).matches("\\d{1,4}")) {
            query = query.substring(0, hash);
        }
        final String q = query;
        List<Member> all = loadAllMembers(guild);
        List<Member> matches = all.stream().filter(m -> nameMatches(m, q, true)).toList();
        if (matches.isEmpty()) {
            matches = all.stream().filter(m -> nameMatches(m, q, false)).toList();
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No member found matching '" + username + "' (checked username, display name and server nickname of " + all.size() + " members)");
        }
        int shown = Math.min(matches.size(), 25);
        return "Found " + matches.size() + " member(s) matching '" + username + "'" + (matches.size() > shown ? " (showing first " + shown + ")" : "") + ":\n"
                + matches.stream().limit(shown).map(m -> "- " + describeMember(m)).collect(Collectors.joining("\n"));
    }

    /**
     * Lists all members of a guild with IDs, usernames, display names and nicknames.
     */
    @Tool(name = "list_members", description = "List all members of a server with user ID, username, display name and server nickname. Use it to build a name-to-ID roster. Paginated: 200 per page by default.")
    public String listMembers(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Page number starting at 1 (default 1)", required = false) String page,
            @ToolParam(description = "Members per page, 1-500 (default 200)", required = false) String pageSize,
            @ToolParam(description = "Include bot accounts: true or false (default false)", required = false) String includeBots) {
        Guild guild = requireGuild(guildId);
        int size = parseIntInRange(pageSize, 200, 1, 500, "pageSize");
        int pageNumber = parseIntInRange(page, 1, 1, Integer.MAX_VALUE, "page");
        boolean bots = "true".equalsIgnoreCase(includeBots);
        List<Member> members = loadAllMembers(guild).stream()
                .filter(m -> bots || !m.getUser().isBot())
                .sorted(java.util.Comparator.comparing(m -> m.getEffectiveName().toLowerCase()))
                .toList();
        int totalPages = Math.max(1, (int) Math.ceil(members.size() / (double) size));
        if (pageNumber > totalPages) {
            throw new IllegalArgumentException("page " + pageNumber + " is past the last page (" + totalPages + ")");
        }
        int from = (pageNumber - 1) * size;
        int to = Math.min(members.size(), from + size);
        return "Server: " + guild.getName() + " | " + members.size() + " members | page " + pageNumber + " of " + totalPages + "\n"
                + members.subList(from, to).stream().map(m -> "- " + describeMember(m)).collect(Collectors.joining("\n"));
    }

    private int parseIntInRange(String value, int defaultValue, int min, int max, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(name + " is out of range");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    /**
     * Sends a private message to a specified Discord user.
     *
     * @param userId  The ID of the user to whom the private message will be sent.
     * @param message The content of the private message.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_private_message", description = "Send a private message to a specific user")
    public String sendPrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                     @ToolParam(description = "Message content") String message) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message sentMessage = user.openPrivateChannel().complete().sendMessage(message).complete();
        // Replying to someone means their pending DMs have been handled.
        dmInbox.markReadFromUser(userId);
        return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    /**
     * Edits a private message sent to a specified Discord user.
     *
     * @param userId     The ID of the user to whom the private message was sent.
     * @param messageId  The ID of the message to be edited.
     * @param newMessage The new content for the message.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_private_message", description = "Edit a private message from a specific user")
    public String editPrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                     @ToolParam(description = "Specific message ID") String messageId,
                                     @ToolParam(description = "New message content") String newMessage) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (newMessage == null || newMessage.isEmpty()) {
            throw new IllegalArgumentException("newMessage cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message messageById = user.openPrivateChannel().complete().retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        Message editedMessage = messageById.editMessage(newMessage).complete();
        return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
    }

    /**
     * Deletes a private message sent to a specified Discord user.
     *
     * @param userId    The ID of the user to whom the private message was sent.
     * @param messageId The ID of the message to be deleted.
     * @return A confirmation message indicating the message was deleted successfully.
     */
    @Tool(name = "delete_private_message", description = "Delete a private message from a specific user")
    public String deletePrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                       @ToolParam(description = "Specific message ID") String messageId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message messageById = user.openPrivateChannel().complete().retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        messageById.delete().queue();
        return "Message deleted successfully";
    }

    /**
     * Reads private message history from a specified Discord user.
     *
     * @param userId  The ID of the user from whom to read the private messages.
     * @param count   Optional number of messages to retrieve (default is 100, max is 100).
     * @param before  Optional message ID to fetch messages before this message.
     * @param after   Optional message ID to fetch messages after this message.
     * @param around  Optional message ID to fetch messages around this message.
     * @return A formatted string containing the retrieved private messages.
     */
    @Tool(name = "read_private_messages", description = "Read the DM conversation with a user, oldest first, including the bot's own replies. Default is the most recent 10 messages in a compact one-line-per-message format (local time, name, text, attachment names); the oldest and newest message IDs are given once at the end for paging. Narrow with before/after/around (message IDs) or since (ISO date-time: only messages at or after that time). Pass verbose=true for per-message IDs and user IDs.")
    public String readPrivateMessages(@ToolParam(description = "Discord user ID") String userId,
                                      @ToolParam(description = "Number of messages to retrieve, 1-100 (default 10)", required = false) String count,
                                      @ToolParam(description = "Message ID to fetch messages before this message", required = false) String before,
                                      @ToolParam(description = "Message ID to fetch messages after this message", required = false) String after,
                                      @ToolParam(description = "Message ID to fetch messages around this message", required = false) String around,
                                      @ToolParam(description = "Only messages sent at or after this time. ISO date-time, e.g. 2026-10-23T08:00:00-10:00", required = false) String since,
                                      @ToolParam(description = "true = full detail per message (IDs, user IDs, usernames). Default false = compact.", required = false) String verbose) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        int limit = parseMessageLimit(count);
        validateCursorParameters(before, after, around);

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        var privateChannel = user.openPrivateChannel().complete();
        List<Message> messages;
        if (isProvided(since)) {
            java.time.Instant sinceTime;
            try {
                sinceTime = java.time.OffsetDateTime.parse(since.trim()).toInstant();
            } catch (java.time.format.DateTimeParseException ex) {
                throw new IllegalArgumentException("since must be an ISO date-time like 2026-10-23T08:00:00-10:00");
            }
            String cursor = Long.toUnsignedString(net.dv8tion.jda.api.utils.TimeUtil.getDiscordTimestamp(sinceTime.toEpochMilli()));
            messages = privateChannel.getHistoryAfter(cursor, limit).complete().getRetrievedHistory();
        } else if (isProvided(before)) {
            messages = privateChannel.getHistoryBefore(before, limit).complete().getRetrievedHistory();
        } else if (isProvided(after)) {
            messages = privateChannel.getHistoryAfter(after, limit).complete().getRetrievedHistory();
        } else if (isProvided(around)) {
            messages = privateChannel.getHistoryAround(around, limit).complete().getRetrievedHistory();
        } else {
            messages = privateChannel.getHistory().retrievePast(limit).complete();
        }
        if (!"true".equalsIgnoreCase(verbose)) {
            return compact.page(messages);
        }
        List<String> formatedMessages = formatMessages(messages);
        return "**Retrieved " + messages.size() + " messages:** \n" + String.join("\n", formatedMessages);
    }

    private int parseMessageLimit(String count) {
        if (count == null || count.isBlank()) {
            return 10;
        }

        int limit;
        try {
            limit = Integer.parseInt(count);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("count must be an integer between 1 and 100");
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("count must be between 1 and 100");
        }
        return limit;
    }

    private void validateCursorParameters(String before, String after, String around) {
        if (before != null && before.isBlank()) {
            throw new IllegalArgumentException("before cannot be blank");
        }
        if (after != null && after.isBlank()) {
            throw new IllegalArgumentException("after cannot be blank");
        }
        if (around != null && around.isBlank()) {
            throw new IllegalArgumentException("around cannot be blank");
        }

        int providedCursors = (isProvided(before) ? 1 : 0)
                + (isProvided(after) ? 1 : 0)
                + (isProvided(around) ? 1 : 0);
        if (providedCursors > 1) {
            throw new IllegalArgumentException("before, after, and around are mutually exclusive; provide only one");
        }
    }

    private boolean isProvided(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Looks a user up globally by ID, so a DM works no matter which of the bot's servers the user is in.
     * Previously this fetched the user as a member of each server in turn, and Discord's
     * "10007: Unknown Member" error from the first server they were not in aborted the lookup.
     */
    private User getUserById(String userId) {
        try {
            return jda.retrieveUserById(userId).complete();
        } catch (ErrorResponseException ex) {
            if (ex.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return null;
            }
            throw ex;
        }
    }

    private List<String> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    String authorName = m.getAuthor().getName();
                    String timestamp = m.getTimeCreated().toString();
                    String content = m.getContentRaw();
                    String msgId = m.getId();

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("- (ID: %s) **[%s | user ID: %s]** `%s`: ```%s```", msgId, authorName, m.getAuthor().getId(), timestamp, content));

                    List<Message.Attachment> attachments = m.getAttachments();
                    if (!attachments.isEmpty()) {
                        sb.append("\n  Attachments:");
                        for (Message.Attachment attachment : attachments) {
                            sb.append("\n    - ").append(formatAttachmentSummary(attachment));
                        }
                    }

                    return sb.toString();
                }).toList();
    }

    private String formatAttachmentSummary(Message.Attachment attachment) {
        return String.format(
                "(Attachment ID: %s) `%s` (%s, %s) URL: %s",
                attachment.getId(),
                attachment.getFileName(),
                formatFileSize(attachment.getSize()),
                attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                attachment.getUrl()
        );
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
