package dev.saseq.services;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compact, token-light rendering of messages for AI agents.
 *
 * The verbose format used by the read tools spends almost half its characters on message IDs,
 * user IDs, UTC timestamps, code fences and mention legends. Agents rarely need those per
 * message, so the compact form is one line per message: local time, the sender's best name
 * (server nickname, else display name, else username), the text with mentions already resolved,
 * and attachment names. The first and last message IDs are given once per page so paging with
 * before/after still works.
 */
@Component
public class CompactFormatter {

    private final ZoneId zone;
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("M/d h:mm a");

    public CompactFormatter(@Value("${DISPLAY_TIMEZONE:${DM_LOG_TIMEZONE:Pacific/Honolulu}}") String timezone) {
        ZoneId z;
        try {
            z = ZoneId.of(timezone == null || timezone.isBlank() ? "Pacific/Honolulu" : timezone.trim());
        } catch (Exception ex) {
            z = ZoneId.of("Pacific/Honolulu");
        }
        this.zone = z;
    }

    public ZoneId zone() {
        return zone;
    }

    public String time(OffsetDateTime t) {
        return t.atZoneSameInstant(zone).format(time);
    }

    /** Server nickname, else global display name, else username. */
    public String bestName(User user, Guild guild) {
        if (guild != null) {
            Member m = guild.getMemberById(user.getId());
            if (m != null && m.getNickname() != null && !m.getNickname().isBlank()) {
                return m.getNickname();
            }
        }
        return user.getGlobalName() != null ? user.getGlobalName() : user.getName();
    }

    /** One line: {@code - 10/23 2:16 PM  Name: text [📎 file.png]}. */
    public String line(Message m) {
        Guild guild = m.isFromGuild() ? m.getGuild() : null;
        String text = m.getContentDisplay();
        if (text.isBlank()) {
            text = m.getAttachments().isEmpty() ? "(no text)" : "(attachment)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(time(m.getTimeCreated())).append("  ")
          .append(bestName(m.getAuthor(), guild)).append(": ").append(text.replace("\n", "\n    "));
        if (m.isEdited()) {
            sb.append(" (edited)");
        }
        for (Message.Attachment a : m.getAttachments()) {
            sb.append(" [📎 ").append(a.getFileName()).append(" ").append(a.getUrl()).append("]");
        }
        return sb.toString();
    }

    /**
     * A page of messages, oldest first, with a one-line footer carrying the IDs needed to page.
     */
    public String page(List<Message> messagesAnyOrder) {
        List<Message> messages = messagesAnyOrder.stream()
                .sorted((a, b) -> a.getTimeCreated().compareTo(b.getTimeCreated()))
                .toList();
        if (messages.isEmpty()) {
            return "No messages.";
        }
        String body = messages.stream().map(this::line).collect(Collectors.joining("\n"));
        Message first = messages.get(0);
        Message last = messages.get(messages.size() - 1);
        return body + "\n(" + messages.size() + " message(s), times " + zone.getId()
                + "; oldest ID " + first.getId() + ", newest ID " + last.getId()
                + "; pass verbose=true for IDs, usernames and mention details)";
    }
}
