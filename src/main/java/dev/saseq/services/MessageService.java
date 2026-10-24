package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.TimeUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class MessageService {

    private final JDA jda;
    private final CompactFormatter compact;

    public MessageService(JDA jda, CompactFormatter compact) {
        this.jda = jda;
        this.compact = compact;
    }

    /**
     * Helper method to get a MessageChannel by ID, checking both text channels and thread channels.
     */
    private MessageChannel getMessageChannelById(String channelId) {
        // First try text channel
        TextChannel textChannel = jda.getTextChannelById(channelId);
        if (textChannel != null) {
            return textChannel;
        }
        // Then try news/announcement channel
        NewsChannel newsChannel = jda.getNewsChannelById(channelId);
        if (newsChannel != null) {
            return newsChannel;
        }
        // Then try thread channel
        ThreadChannel threadChannel = jda.getThreadChannelById(channelId);
        if (threadChannel != null) {
            return threadChannel;
        }
        return null;
    }

    /**
     * Sends a message to a specified Discord channel.
     *
     * @param channelId The ID of the channel where the message will be sent.
     * @param message   The content of the message to be sent.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_message", description = "Send a message to a specific channel")
    public String sendMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Message content") String message) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message sentMessage = channel.sendMessage(message).complete();
        return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    /**
     * Edits an existing message in a specified Discord channel.
     *
     * @param channelId  The ID of the channel containing the message.
     * @param messageId  The ID of the message to be edited.
     * @param newMessage The new content for the message.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_message", description = "Edit a message from a specific channel")
    public String editMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Specific message ID") String messageId,
                              @ToolParam(description = "New message content") String newMessage) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (newMessage == null || newMessage.isEmpty()) {
            throw new IllegalArgumentException("newMessage cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        Message editedMessage = messageById.editMessage(newMessage).complete();
        return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
    }

    /**
     * Deletes a message from a specified Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to be deleted.
     * @return A confirmation message indicating the message was deleted successfully.
     */
    @Tool(name = "delete_message", description = "Delete a message from a specific channel")
    public String deleteMessage(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Specific message ID") String messageId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        messageById.delete().queue();
        return "Message deleted successfully";
    }

    /**
     * Reads message history from a specified Discord channel.
     *
     * @param channelId The ID of the channel from which to read messages.
     * @param count     Optional number of messages to retrieve (default is 100, max is 100).
     * @param before    Optional message ID to fetch messages before this message.
     * @param after     Optional message ID to fetch messages after this message.
     * @param around    Optional message ID to fetch messages around this message.
     * @return A formatted string containing the retrieved messages.
     */
    @Tool(name = "read_messages", description = "Read message history from a specific channel, oldest first. Default output is compact: one line per message with local time, the sender's name (server nickname when set), the text with mentions resolved, and attachment names; the oldest and newest message IDs are given once at the end for paging. Pass verbose=true to get per-message IDs, user IDs, usernames and mention details. Use count to limit how many messages come back, and before/after/around (message IDs) or since/until (dates) to narrow the range.")
    public String readMessages(@ToolParam(description = "Discord channel ID") String channelId,
                               @ToolParam(description = "Number of messages to retrieve, 1-100 (default 25)", required = false) String count,
                               @ToolParam(description = "Message ID to fetch messages before this message", required = false) String before,
                               @ToolParam(description = "Message ID to fetch messages after this message", required = false) String after,
                               @ToolParam(description = "Message ID to fetch messages around this message", required = false) String around,
                               @ToolParam(description = "Same as count; accepted for convenience", required = false) String limit,
                               @ToolParam(description = "Only messages sent at or after this time. ISO date or date-time, e.g. 2026-10-07 or 2026-10-07T08:00:00-10:00 (a bare date means 00:00 UTC). Returns the oldest matching messages first.", required = false) String since,
                               @ToolParam(description = "Only messages sent before this time. Same format as since.", required = false) String until,
                               @ToolParam(description = "true = full detail per message (IDs, user IDs, usernames, mention legend). Default false = compact one-line format.", required = false) String verbose) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        int max = parseMessageLimit(isProvided(count) ? count : limit);
        validateCursorParameters(before, after, around);
        Instant sinceTime = parseTime(since, "since");
        Instant untilTime = parseTime(until, "until");
        if ((sinceTime != null || untilTime != null) && (isProvided(before) || isProvided(after) || isProvided(around))) {
            throw new IllegalArgumentException("since/until cannot be combined with before/after/around");
        }
        if (sinceTime != null && untilTime != null && !sinceTime.isBefore(untilTime)) {
            throw new IllegalArgumentException("since must be earlier than until");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        List<Message> messages;
        if (isProvided(before)) {
            messages = channel.getHistoryBefore(before, max).complete().getRetrievedHistory();
        } else if (isProvided(after)) {
            messages = channel.getHistoryAfter(after, max).complete().getRetrievedHistory();
        } else if (isProvided(around)) {
            messages = channel.getHistoryAround(around, max).complete().getRetrievedHistory();
        } else if (sinceTime != null) {
            String cursor = Long.toUnsignedString(TimeUtil.getDiscordTimestamp(sinceTime.toEpochMilli()));
            messages = new ArrayList<>(channel.getHistoryAfter(cursor, max).complete().getRetrievedHistory());
            if (untilTime != null) {
                messages.removeIf(m -> !m.getTimeCreated().toInstant().isBefore(untilTime));
            }
        } else if (untilTime != null) {
            String cursor = Long.toUnsignedString(TimeUtil.getDiscordTimestamp(untilTime.toEpochMilli()));
            messages = channel.getHistoryBefore(cursor, max).complete().getRetrievedHistory();
        } else {
            messages = channel.getHistory().retrievePast(max).complete();
        }
        if (!"true".equalsIgnoreCase(verbose)) {
            return compact.page(messages);
        }
        List<String> formatedMessages = formatMessages(messages);
        return "**Retrieved " + messages.size() + " messages:** \n" + String.join("\n", formatedMessages);
    }

    /**
     * One call that returns everything new across many channels, so a periodic sweep is a single
     * request instead of one read_messages call per channel.
     */
    @Tool(name = "get_updates_since", description = "Return all new messages across channels since a point in time, grouped by server and channel, oldest first. Use this for a periodic sweep instead of calling read_messages on each channel. Give either sinceMinutes (e.g. 30) or since (ISO date-time). By default covers every text channel the bot can read in every server; pass channelIds to narrow it. Messages from bots are skipped unless includeBots is true. A channel that hits limitPerChannel is marked truncated; use read_messages with after to page it. The reply ends with nextSince: pass that value as since on the next sweep so consecutive sweeps neither overlap nor leave a gap. Output is compact by default (local time, name, text); verbose=true adds IDs, user IDs and mention details.")
    public String getUpdatesSince(@ToolParam(description = "Look back this many minutes (1-10080). Ignored if since is given.", required = false) String sinceMinutes,
                                  @ToolParam(description = "Only messages sent at or after this time. ISO date-time, e.g. 2026-10-07T08:00:00-10:00", required = false) String since,
                                  @ToolParam(description = "Comma-separated channel IDs to check. Omit to check every readable text channel in every server.", required = false) String channelIds,
                                  @ToolParam(description = "Max messages per channel, 1-100 (default 50)", required = false) String limitPerChannel,
                                  @ToolParam(description = "true = include messages posted by bots (default false)", required = false) String includeBots,
                                  @ToolParam(description = "true = full detail per message (IDs, user IDs, usernames, mention legend). Default false = compact one-line format.", required = false) String verbose) {
        Instant sinceTime = parseTime(since, "since");
        boolean full = "true".equalsIgnoreCase(verbose);
        if (sinceTime == null) {
            int minutes = 30;
            if (isProvided(sinceMinutes)) {
                try {
                    minutes = Integer.parseInt(sinceMinutes.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("sinceMinutes must be a whole number of minutes");
                }
                if (minutes < 1 || minutes > 10080) {
                    throw new IllegalArgumentException("sinceMinutes must be between 1 and 10080 (7 days)");
                }
            }
            sinceTime = Instant.now().minusSeconds(60L * minutes);
        }
        int perChannel = parseMessageLimit(isProvided(limitPerChannel) ? limitPerChannel : "50");
        boolean bots = "true".equalsIgnoreCase(includeBots);
        String cursor = Long.toUnsignedString(TimeUtil.getDiscordTimestamp(sinceTime.toEpochMilli()));

        List<MessageChannel> channels = new ArrayList<>();
        if (isProvided(channelIds)) {
            for (String id : channelIds.split(",")) {
                if (id.isBlank()) {
                    continue;
                }
                MessageChannel c = getMessageChannelById(id.trim());
                if (c == null) {
                    throw new IllegalArgumentException("Channel not found by channelId: " + id.trim());
                }
                channels.add(c);
            }
        } else {
            for (Guild guild : jda.getGuilds()) {
                for (TextChannel c : guild.getTextChannels()) {
                    if (guild.getSelfMember().hasPermission(c, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
                        channels.add(c);
                    }
                }
            }
        }

        // Taken before any channel is read, so passing it back as `since` next time leaves no gap.
        Instant sweepStart = Instant.now();
        long cursorLong = Long.parseUnsignedLong(cursor);

        StringBuilder out = new StringBuilder();
        int total = 0;
        int channelsWithNews = 0;
        int skipped = 0;
        for (MessageChannel channel : channels) {
            // JDA tracks each channel's latest message ID from the gateway; if nothing has been posted
            // since the cursor there is no need to hit the API at all. 0 means unknown, so read it.
            long latest = channel.getLatestMessageIdLong();
            if (latest != 0 && Long.compareUnsigned(latest, cursorLong) <= 0) {
                skipped++;
                continue;
            }
            List<Message> messages;
            try {
                messages = new ArrayList<>(channel.getHistoryAfter(cursor, perChannel).complete().getRetrievedHistory());
            } catch (Exception ex) {
                out.append("\n### ").append(describeChannel(channel)).append("\n  (could not read: ").append(ex.getMessage()).append(")\n");
                continue;
            }
            boolean truncated = messages.size() >= perChannel;
            if (!bots) {
                messages.removeIf(m -> m.getAuthor().isBot());
            }
            if (messages.isEmpty()) {
                if (truncated) {
                    // The page was full of bot posts; human messages may be hiding behind them.
                    out.append("\n### ").append(describeChannel(channel))
                            .append(" (0 shown, truncated: page was all bot messages; use read_messages with after to page)\n");
                }
                continue;
            }
            messages.sort((a, b) -> a.getTimeCreated().compareTo(b.getTimeCreated()));
            channelsWithNews++;
            total += messages.size();
            out.append("\n### ").append(describeChannel(channel))
                    .append(" (").append(messages.size()).append(truncated ? ", truncated" : "")
                    .append(full ? "" : "; newest ID " + messages.get(messages.size() - 1).getId()).append(")\n");
            if (full) {
                out.append(String.join("\n", formatMessages(messages))).append("\n");
            } else {
                out.append(messages.stream().map(compact::line).collect(java.util.stream.Collectors.joining("\n"))).append("\n");
            }
        }
        String footer = "\nnextSince: " + sweepStart + "  (pass this as `since` on the next sweep for a gap-free follow-on"
                + (full ? ")" : "; times " + compact.zone().getId() + ", verbose=true for IDs and usernames)");
        if (total == 0 && out.isEmpty()) {
            return "No new messages since " + sinceTime + " in " + channels.size() + " channel(s)"
                    + (skipped > 0 ? " (" + skipped + " had no activity and were not read)" : "") + "." + footer;
        }
        return "**" + total + " new message(s) in " + channelsWithNews + " of " + channels.size()
                + " channel(s) since " + sinceTime + ":**\n" + out + footer;
    }

    private String describeChannel(MessageChannel channel) {
        String guildName = "";
        if (channel instanceof TextChannel tc) {
            guildName = tc.getGuild().getName() + " / ";
        } else if (channel instanceof ThreadChannel th) {
            guildName = th.getGuild().getName() + " / ";
        } else if (channel instanceof NewsChannel nc) {
            guildName = nc.getGuild().getName() + " / ";
        }
        return guildName + "#" + channel.getName() + " (ID: " + channel.getId() + ")";
    }

    private Instant parseTime(String value, String name) {
        if (!isProvided(value)) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return java.time.LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(name + " must be an ISO date or date-time, e.g. 2026-10-07 or 2026-10-07T08:00:00-10:00");
        }
    }

    private int parseMessageLimit(String count) {
        if (count == null || count.isBlank()) {
            return 25;
        }

        int limit;
        try {
            limit = Integer.parseInt(count.trim());
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
     * Adds a reaction (emoji) to a specific message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to which the reaction will be added.
     * @param emoji     The emoji to add as a reaction (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message that was reacted to.
     */
    @Tool(name = "add_reaction", description = "Add a reaction (emoji) to a specific message")
    public String addReaction(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Discord message ID") String messageId,
                              @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.addReaction(Emoji.fromUnicode(emoji)).queue();
        return "Added reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Removes a specified reaction (emoji) from a message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message from which the reaction will be removed.
     * @param emoji     The emoji to remove from the message (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message.
     */
    @Tool(name = "remove_reaction", description = "Remove a specified reaction (emoji) from a message")
    public String removeReaction(@ToolParam(description = "Discord channel ID") String channelId,
                                 @ToolParam(description = "Discord message ID") String messageId,
                                 @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.removeReaction(Emoji.fromUnicode(emoji)).queue();
        return "Removed reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Retrieves attachment metadata from a specific message in a Discord channel.
     *
     * @param channelId    The ID of the channel containing the message.
     * @param messageId    The ID of the message to retrieve attachments from.
     * @param attachmentId Optional ID of a specific attachment (if omitted, returns all).
     * @return A formatted string containing attachment metadata.
     */
    @Tool(name = "get_attachment", description = "Get attachment metadata (filename, size, content type, URLs) from a specific message. Returns info only, does not download files.")
    public String getAttachment(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Discord message ID") String messageId,
                                @ToolParam(description = "Specific attachment ID (omit to get all attachments)", required = false) String attachmentId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }

        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return "This message has no attachments.";
        }

        if (attachmentId != null && !attachmentId.isEmpty()) {
            Message.Attachment attachment = attachments.stream()
                    .filter(a -> a.getId().equals(attachmentId))
                    .findFirst()
                    .orElse(null);
            if (attachment == null) {
                throw new IllegalArgumentException("Attachment not found by attachmentId");
            }
            return formatAttachmentDetail(attachment);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Found ").append(attachments.size()).append(" attachment(s):**\n");
        for (Message.Attachment attachment : attachments) {
            sb.append(formatAttachmentDetail(attachment)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatAttachmentDetail(Message.Attachment attachment) {
        return String.format(
                "- %s\n  Proxy URL: %s",
                formatAttachmentSummary(attachment),
                attachment.getProxyUrl()
        );
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

    private List<String> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    User author = m.getAuthor();
                    Guild guild = m.isFromGuild() ? m.getGuild() : null;
                    String timestamp = m.getTimeCreated().toString();
                    String content = m.getContentRaw();
                    String msgId = m.getId();

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("- (ID: %s) **[%s]** `%s`: ```%s```", msgId, describeUser(author, guild), timestamp, content));

                    List<String> mentions = new ArrayList<>();
                    for (User u : m.getMentions().getUsers()) {
                        mentions.add("<@" + u.getId() + "> = " + describeUser(u, guild));
                    }
                    for (Role r : m.getMentions().getRoles()) {
                        mentions.add("<@&" + r.getId() + "> = role " + r.getName());
                    }
                    m.getMentions().getChannels().forEach(c -> mentions.add("<#" + c.getId() + "> = channel " + c.getName()));
                    if (m.getMentions().mentionsEveryone()) {
                        mentions.add("@everyone/@here");
                    }
                    if (!mentions.isEmpty()) {
                        sb.append("\n  Mentions: ").append(String.join("; ", mentions));
                    }

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

    /**
     * "username | user ID: 123 | display name: X | nickname: Y" for a message author or mentioned user.
     */
    private String describeUser(User user, Guild guild) {
        StringBuilder sb = new StringBuilder(user.getName());
        sb.append(" | user ID: ").append(user.getId());
        if (user.getGlobalName() != null) {
            sb.append(" | display name: ").append(user.getGlobalName());
        }
        Member member = guild != null ? guild.getMemberById(user.getId()) : null;
        if (member != null && member.getNickname() != null) {
            sb.append(" | nickname: ").append(member.getNickname());
        }
        if (user.isBot()) {
            sb.append(" | BOT");
        }
        return sb.toString();
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
