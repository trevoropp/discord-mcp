package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mirrors every DM conversation the bot has into a per-server log channel, so managers can read
 * what members are DMing the bot (and what it replies) without going through an agent.
 *
 * Configure {@code DM_LOG_CHANNELS} as comma-separated {@code guildId:channelId} pairs. When a DM
 * arrives, or the bot sends one, the other party's server memberships decide where it is logged:
 * a copy goes to the log channel of every mapped server they belong to. Someone in none of the
 * mapped servers is logged to {@code DM_LOG_FALLBACK_CHANNEL_ID} if set, otherwise skipped.
 *
 * Both directions are logged (incoming DMs and the bot's own replies), one post per message,
 * with the time shown in {@code DM_LOG_TIMEZONE} (default Pacific/Honolulu). Edits are logged
 * as a follow-up post. Content is posted verbatim, so the log channels should be visible to
 * management only. Logging never throws: a failure is printed and the message is simply not
 * mirrored.
 */
@Service
public class DmLogService extends ListenerAdapter {

    private static final int DISCORD_MESSAGE_LIMIT = 2000;

    private final JDA jda;
    private final Map<String, String> logChannelByGuild;
    private final String fallbackChannelId;
    private final ZoneId zone;
    private final DateTimeFormatter timeFormat;

    public DmLogService(JDA jda,
                        @Value("${DM_LOG_CHANNELS:}") String logChannels,
                        @Value("${DM_LOG_FALLBACK_CHANNEL_ID:}") String fallbackChannelId,
                        @Value("${DM_LOG_TIMEZONE:Pacific/Honolulu}") String timezone) {
        this.jda = jda;
        this.logChannelByGuild = parseMapping(logChannels);
        this.fallbackChannelId = fallbackChannelId == null ? "" : fallbackChannelId.trim();
        ZoneId z;
        try {
            z = ZoneId.of(timezone == null || timezone.isBlank() ? "Pacific/Honolulu" : timezone.trim());
        } catch (Exception ex) {
            System.err.println("DM_LOG_TIMEZONE '" + timezone + "' is not a valid zone; using Pacific/Honolulu");
            z = ZoneId.of("Pacific/Honolulu");
        }
        this.zone = z;
        this.timeFormat = DateTimeFormatter.ofPattern("EEE M/d h:mm a");
        if (!logChannelByGuild.isEmpty()) {
            System.out.println("DM log enabled: mirroring DMs to " + logChannelByGuild.size()
                    + " server log channel(s)" + (this.fallbackChannelId.isEmpty() ? "" : " plus a fallback channel"));
            jda.addEventListener(this);
        }
    }

    private static Map<String, String> parseMapping(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                map.put(parts[0].trim(), parts[1].trim());
            } else if (!pair.isBlank()) {
                System.err.println("DM_LOG_CHANNELS: ignoring malformed entry '" + pair + "' (expected guildId:channelId)");
            }
        }
        return map;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getChannelType() != ChannelType.PRIVATE) {
            return;
        }
        log(event.getMessage(), false);
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (event.getChannelType() != ChannelType.PRIVATE) {
            return;
        }
        log(event.getMessage(), true);
    }

    private void log(Message message, boolean edited) {
        try {
            User author = message.getAuthor();
            boolean fromBot = author.getIdLong() == jda.getSelfUser().getIdLong();
            if (author.isBot() && !fromBot) {
                return;
            }
            // The member whose conversation this is: the sender for incoming DMs, the recipient for the bot's replies.
            User member = fromBot ? otherParty(message) : author;
            if (member == null) {
                return;
            }
            List<TextChannel> targets = targetsFor(member);
            if (targets.isEmpty()) {
                return;
            }
            String text = render(message, member, fromBot, edited);
            for (TextChannel channel : targets) {
                channel.sendMessage(text)
                        .setSuppressedNotifications(true)
                        .queue(null, err -> System.err.println("DM log: could not post to #" + channel.getName()
                                + " (" + channel.getId() + "): " + err.getMessage()));
            }
        } catch (Exception ex) {
            System.err.println("DM log: failed to mirror message " + message.getId() + ": " + ex.getMessage());
        }
    }

    private User otherParty(Message message) {
        if (message.getChannelType() != ChannelType.PRIVATE) {
            return null;
        }
        PrivateChannel pc = message.getChannel().asPrivateChannel();
        return pc.getUser();
    }

    private List<TextChannel> targetsFor(User member) {
        List<TextChannel> targets = new ArrayList<>();
        for (Map.Entry<String, String> e : logChannelByGuild.entrySet()) {
            Guild guild = jda.getGuildById(e.getKey());
            if (guild == null) {
                continue;
            }
            Member m = guild.getMember(member);
            if (m == null) {
                continue;
            }
            TextChannel channel = jda.getTextChannelById(e.getValue());
            if (channel != null) {
                targets.add(channel);
            } else {
                System.err.println("DM log: channel " + e.getValue() + " for server " + guild.getName() + " not found");
            }
        }
        if (targets.isEmpty() && !fallbackChannelId.isEmpty()) {
            TextChannel fallback = jda.getTextChannelById(fallbackChannelId);
            if (fallback != null) {
                targets.add(fallback);
            }
        }
        return targets;
    }

    private String render(Message message, User member, boolean fromBot, boolean edited) {
        String memberName = member.getGlobalName() != null ? member.getGlobalName() : member.getName();
        String speaker = fromBot ? jda.getSelfUser().getName() : memberName;
        String when = (edited && message.getTimeEdited() != null ? message.getTimeEdited() : message.getTimeCreated())
                .atZoneSameInstant(zone).format(timeFormat);
        String direction = fromBot ? "→ " + memberName : "";

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(memberName).append("** (").append(member.getName()).append(") | ")
          .append(when).append(edited ? " | edited" : "").append("\n");
        sb.append(speaker).append(direction.isEmpty() ? "" : " " + direction).append(": ");

        String content = message.getContentDisplay();
        if (content.isBlank()) {
            content = message.getAttachments().isEmpty() ? "(no text)" : "(attachment only)";
        }
        String attachments = message.getAttachments().stream()
                .map(a -> "\n  📎 " + a.getFileName() + " " + a.getUrl())
                .collect(Collectors.joining());

        int room = DISCORD_MESSAGE_LIMIT - sb.length() - attachments.length() - 8;
        if (content.length() > room) {
            content = content.substring(0, Math.max(0, room)) + " […]";
        }
        sb.append("\"").append(content).append("\"").append(attachments);
        return sb.toString();
    }
}
