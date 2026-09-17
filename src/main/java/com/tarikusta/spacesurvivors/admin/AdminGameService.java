package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.game.Announcement;
import com.tarikusta.spacesurvivors.game.GameContentService;
import com.tarikusta.spacesurvivors.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * What the backoffice may change about the live game: the main-menu announcement (and, next, the
 * remote game settings).
 *
 * <p>ADMIN only. Everything here reaches every player the next time they open the menu — the same
 * reasoning that made test scores ADMIN only.</p>
 *
 * <p>The writes live here, in the admin package, and the reads in {@code game}: the public endpoint
 * the game calls has no business depending on the backoffice's audit trail.</p>
 */
@Service
@PreAuthorize(AdminRole.IS_ADMIN)
public class AdminGameService {

    private static final Logger log = LoggerFactory.getLogger(AdminGameService.class);

    public enum Outcome { SAVED, CLEARED, NOTHING_TO_CLEAR, INVALID }

    public record Result(Outcome outcome, String problem) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final AppSettings settings;
    private final GameContentService content;
    private final AdminAudit audit;
    private final ObjectMapper json;

    public AdminGameService(AppSettings settings, GameContentService content, AdminAudit audit,
                            ObjectMapper json) {
        this.settings = settings;
        this.content = content;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Optional<Announcement> announcement() {
        return content.announcement();
    }

    /**
     * Publish an announcement, replacing any current one.
     *
     * <p>Line breaks and tabs become single spaces: the game draws this as one line in a banner, and
     * a message that renders differently from how it was typed is a message somebody sends twice.</p>
     */
    @Transactional
    public Result setAnnouncement(String actor, String message, String level, String callerIp) {
        String text = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return new Result(Outcome.INVALID, "The message is empty.");
        }
        if (text.length() > Announcement.MAX_LENGTH) {
            return new Result(Outcome.INVALID, "The message is " + text.length()
                    + " characters; the limit is " + Announcement.MAX_LENGTH + ".");
        }
        Optional<String> parsed = Announcement.parseLevel(level);
        if (parsed.isEmpty()) {
            return new Result(Outcome.INVALID, "Level must be info or warning.");
        }

        ObjectNode value = json.createObjectNode();
        value.put("message", text);
        value.put("level", parsed.get());
        settings.put(Announcement.SETTING_KEY, value, actor);

        audit.announcementSet(actor, parsed.get(), text, callerIp);
        log.info("admin '{}' published a {} announcement", actor, parsed.get());
        return Result.of(Outcome.SAVED);
    }

    @Transactional
    public Result clearAnnouncement(String actor, String callerIp) {
        if (!settings.remove(Announcement.SETTING_KEY)) {
            return Result.of(Outcome.NOTHING_TO_CLEAR);
        }
        audit.announcementCleared(actor, callerIp);
        log.info("admin '{}' cleared the announcement", actor);
        return Result.of(Outcome.CLEARED);
    }
}
