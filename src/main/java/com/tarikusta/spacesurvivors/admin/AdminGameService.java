package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.game.Announcement;
import com.tarikusta.spacesurvivors.game.GameContentService;
import com.tarikusta.spacesurvivors.game.GameTunable;
import com.tarikusta.spacesurvivors.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the backoffice may change about the live game: the main-menu announcement and the remote
 * game settings.
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

    public enum Outcome { SAVED, CLEARED, NOTHING_TO_CLEAR, INVALID, UNCHANGED }

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

    /** The current overrides, as the form shows them (plain strings, no trailing zeros). */
    @Transactional(readOnly = true)
    public Map<String, String> gameConfigForm() {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        content.gameConfig().forEach((key, value) -> form.put(key, value.toPlainString()));
        return form;
    }

    /**
     * Replace the game settings from the form: a blank field means "back to the game's own value".
     *
     * <p>All or nothing, like the save editor — one field out of range saves none of them, so the
     * live configuration is never half of what somebody meant. Audited with every change, old value
     * and new.</p>
     */
    @Transactional
    public Result saveGameConfig(String actor, Map<String, String> submitted, String callerIp) {
        Map<String, BigDecimal> before = content.gameConfig();
        Map<String, BigDecimal> after = new java.util.LinkedHashMap<>();

        for (GameTunable tunable : GameTunable.values()) {
            String raw = submitted.getOrDefault(tunable.key(), "").trim();
            if (raw.isEmpty()) {
                continue;
            }
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(raw.replace(',', '.'));
            } catch (NumberFormatException notANumber) {
                return new Result(Outcome.INVALID, tunable.label() + " must be a number.");
            }
            Optional<BigDecimal> accepted = tunable.accept(parsed);
            if (accepted.isEmpty()) {
                return new Result(Outcome.INVALID, tunable.label() + " must be "
                        + (tunable.whole() ? "a whole number " : "") + "from " + tunable.min()
                        + " to " + tunable.max() + ".");
            }
            after.put(tunable.key(), accepted.get());
        }

        List<String> changes = new ArrayList<>();
        for (GameTunable tunable : GameTunable.values()) {
            BigDecimal was = before.get(tunable.key());
            BigDecimal now = after.get(tunable.key());
            boolean same = was == null ? now == null : now != null && was.compareTo(now) == 0;
            if (!same) {
                changes.add(tunable.key() + " " + (was == null ? "default" : was.toPlainString())
                        + " → " + (now == null ? "default" : now.toPlainString()));
            }
        }
        if (changes.isEmpty()) {
            return Result.of(Outcome.UNCHANGED);
        }

        if (after.isEmpty()) {
            settings.remove(GameTunable.SETTING_KEY);
        } else {
            ObjectNode value = json.createObjectNode();
            after.forEach(value::put);
            settings.put(GameTunable.SETTING_KEY, value, actor);
        }

        audit.gameConfigChanged(actor, changes, callerIp);
        log.info("admin '{}' changed the game settings: {}", actor, changes);
        return Result.of(Outcome.SAVED);
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
