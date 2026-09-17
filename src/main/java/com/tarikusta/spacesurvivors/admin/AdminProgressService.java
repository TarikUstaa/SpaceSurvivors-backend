package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import com.tarikusta.spacesurvivors.progress.PlayerProgress;
import com.tarikusta.spacesurvivors.progress.PlayerProgressRepository;
import com.tarikusta.spacesurvivors.progress.ProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Editing a player's cloud save by hand — mostly so that testing does not mean playing for an hour
 * to reach the state being tested.
 *
 * <h2>This is the one place the server opens the save</h2>
 *
 * <p>D1 made the save the client's: one JSON object the server stores and never interprets. This
 * class breaks that on purpose, and narrowly. It reads and writes <b>only the fields named in
 * {@link #COUNTERS} and {@link #LISTS}</b>, and leaves every other property exactly as it found
 * it — so a field the game adds next month survives an edit made by a server that has never heard
 * of it.</p>
 *
 * <h2>Why an edit needs {@code adminRevision}, and not just an UPDATE</h2>
 *
 * <p>The game keeps its own copy and reconciles the two with {@code ProfileMerge}, whose rule is
 * "never lose progress": lifetime counters take the higher value, achievements are a union, and the
 * wallet comes from whichever side has earned more scrap. Every one of those rules undoes a manual
 * edit. Lower a best score and the device raises it back; remove an achievement and the device adds
 * it back; raise the wallet alone and the device — with the same lifetime scrap, so a tie, so its
 * own copy wins — writes its old balance over it.</p>
 *
 * <p>So an edit also increments {@code adminRevision} inside the save. The game compares that
 * number before anything else: a server copy with a higher one is taken whole, replacing the local
 * one, instead of being merged. That is what makes an edit here mean what it says, in both
 * directions. It costs the player whatever they did locally since their last sync, which is the
 * right trade for an operator's deliberate correction and the reason this is not a button players
 * can press.</p>
 *
 * <p>Bumping {@code version} (which saving the row does) is what makes the game <em>notice</em>:
 * its next PUT carries the old version, gets a 409 with this copy in the body, and merges — at
 * which point the revision decides.</p>
 *
 * <p>Both roles may edit — it is SUPPORT's main job. The edit is audited with every field's old and
 * new value, because the save itself keeps only the new ones.</p>
 */
@Service
@PreAuthorize(AdminRole.IS_STAFF)
public class AdminProgressService {

    private static final Logger log = LoggerFactory.getLogger(AdminProgressService.class);

    static final String REVISION = "adminRevision";

    /** Numbers in the save, with the largest value the client's field type can hold. */
    static final Map<String, Long> COUNTERS = orderedCounters();

    /** Lists of ids in the save. */
    static final List<String> LISTS = List.of("unlockedAchievementIds", "ownedShipIds");

    static final String UPGRADES = "metaUpgradeLevels";
    static final String SELECTED_SHIP = "selectedShipId";

    /**
     * Hulls the game treats as owned without ever writing them into {@code ownedShipIds}: its
     * {@code ShipService} counts the first catalogue entry and any free hull as owned implicitly.
     * Today that is exactly one ship. The server has no catalogue, so the list is copied here by
     * hand — if the game ever adds a second free hull, it has to be added here too, or selecting
     * it from this form will be refused.
     */
    static final Set<String> IMPLICITLY_OWNED_SHIPS = Set.of("starter");

    /** Every id the game uses today looks like this: {@code first_blood}, {@code vanguard}. */
    private static final Pattern ID = Pattern.compile("^[a-z0-9_]{1,64}$");

    /** An upgrade level is a small number; this only keeps a typo from becoming a billion. */
    private static final int MAX_UPGRADE_LEVEL = 1000;

    private static Map<String, Long> orderedCounters() {
        // Ordered so the form and the audit summary list fields in the same, sensible order.
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("wallet", Long.MAX_VALUE);
        counters.put("lifetimeScrap", Long.MAX_VALUE);
        counters.put("lifetimeKills", Long.MAX_VALUE);
        counters.put("runsPlayed", (long) Integer.MAX_VALUE);
        counters.put("bestKills", (long) Integer.MAX_VALUE);
        counters.put("bestSurvivalSeconds", (long) Integer.MAX_VALUE);
        counters.put("bestLevel", (long) Integer.MAX_VALUE);
        counters.put("bossKills", (long) Integer.MAX_VALUE);
        return Collections.unmodifiableMap(counters);
    }

    public enum Outcome { SAVED, UNCHANGED, NO_SAVE, STALE, INVALID }

    /**
     * @param problem for {@link Outcome#INVALID}, the sentence saying which field and why
     * @param changes for {@link Outcome#SAVED}, one worded entry per field that changed
     */
    public record Result(Outcome outcome, String problem, List<String> changes) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null, List.of());
        }

        static Result invalid(String problem) {
            return new Result(Outcome.INVALID, problem, List.of());
        }
    }

    /**
     * What the edit form is filled with. Every value is already the text that goes in the input,
     * so the template does no formatting and holds no opinion about the save.
     *
     * @param version the save's version when the form was drawn; sent back with the edit so a
     *                save the game changed in the meantime is refused rather than overwritten
     */
    public record Form(UUID playerId, String displayName, int version, long adminRevision,
                       Map<String, String> counters, String unlockedAchievementIds,
                       String ownedShipIds, String selectedShipId, String metaUpgradeLevels) {
    }

    private final AdminPlayerQueries players;
    private final PlayerProgressRepository progress;
    private final ObjectMapper json;
    private final AdminAudit audit;

    public AdminProgressService(AdminPlayerQueries players, PlayerProgressRepository progress,
                                ObjectMapper json, AdminAudit audit) {
        this.players = players;
        this.progress = progress;
        this.json = json;
        this.audit = audit;
    }

    /** The form for one player's save, or empty if they have never saved. */
    @Transactional(readOnly = true)
    public Optional<Form> form(UUID playerId) {
        AdminPlayerDetail player = players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));

        return progress.findById(playerId).map(row -> {
            JsonNode save = json.readTree(row.getProgressData());

            Map<String, String> counters = new LinkedHashMap<>();
            COUNTERS.keySet().forEach(name -> counters.put(name,
                    String.valueOf(save.path(name).asLong(0))));

            return new Form(playerId, player.displayName(), row.getVersion(),
                    save.path(REVISION).asLong(0), counters,
                    String.join("\n", ids(save.path("unlockedAchievementIds"))),
                    String.join("\n", ids(save.path("ownedShipIds"))),
                    save.path(SELECTED_SHIP).asString(""),
                    String.join("\n", upgradeLines(save.path(UPGRADES))));
        });
    }

    /**
     * Apply an edit.
     *
     * <p>Validation happens in full before anything is touched, and the save is either written
     * with every change or not at all — a form with one bad field changes nothing, rather than the
     * fields above the mistake.</p>
     *
     * @param submitted the raw form, as the browser sent it
     */
    @Transactional
    public Result edit(String actor, UUID playerId, Map<String, String> submitted, String callerIp) {
        AdminPlayerDetail player = players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));

        PlayerProgress row = progress.findById(playerId).orElse(null);
        if (row == null) {
            return Result.of(Outcome.NO_SAVE);
        }

        // Checked by hand, before writing, for the reason ProgressService.save gives: a raised
        // optimistic-lock exception marks the transaction rollback-only.
        if (!String.valueOf(row.getVersion()).equals(submitted.get("version"))) {
            return Result.of(Outcome.STALE);
        }

        // ── parse everything first ──────────────────────────────────────────────────────
        Map<String, Long> counters = new LinkedHashMap<>();
        for (Map.Entry<String, Long> field : COUNTERS.entrySet()) {
            Long value = parseCount(submitted.get(field.getKey()), field.getValue());
            if (value == null) {
                return Result.invalid(field.getKey() + " must be a whole number from 0 to "
                        + field.getValue() + ".");
            }
            counters.put(field.getKey(), value);
        }

        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String name : LISTS) {
            List<String> ids = parseIds(submitted.get(name));
            if (ids == null) {
                return Result.invalid(name + ": every id must be lowercase letters, digits or "
                        + "underscores, one per line.");
            }
            lists.put(name, ids);
        }

        Map<String, Integer> upgrades = parseUpgrades(submitted.get(UPGRADES));
        if (upgrades == null) {
            return Result.invalid(UPGRADES + ": one 'id=level' per line, level from 0 to "
                    + MAX_UPGRADE_LEVEL + ".");
        }

        String selectedShip = trim(submitted.get(SELECTED_SHIP));
        if (!selectedShip.isEmpty()
                && !IMPLICITLY_OWNED_SHIPS.contains(selectedShip)
                && !lists.get("ownedShipIds").contains(selectedShip)) {
            // Refused rather than quietly corrected. The game would clear a selection it does not
            // own back to blank — the starter — so this would not break anything, but a form that
            // saves something other than what was typed is a form nobody can trust.
            //
            // Blank and the starter are both valid: the starter is owned without being listed, so
            // checking only ownedShipIds made every save that had picked it uneditable until the
            // operator cleared the field.
            return Result.invalid(SELECTED_SHIP
                    + " must be one of the owned ships, the starter, or empty.");
        }

        // ── compare, then write ─────────────────────────────────────────────────────────
        ObjectNode save = (ObjectNode) json.readTree(row.getProgressData());
        List<String> changes = new ArrayList<>();

        counters.forEach((name, value) -> {
            long before = save.path(name).asLong(0);
            if (before != value) {
                changes.add(name + " " + before + " → " + value);
                // Written as the width the client declares, so the JSON reads the same as a
                // save the game wrote itself.
                if (COUNTERS.get(name) <= Integer.MAX_VALUE) {
                    save.put(name, value.intValue());
                } else {
                    save.put(name, value.longValue());
                }
            }
        });

        lists.forEach((name, ids) -> {
            List<String> before = ids(save.path(name));
            if (!before.equals(ids)) {
                changes.add(describeListChange(name, before, ids));
                ArrayNode array = save.putArray(name);
                ids.forEach(array::add);
            }
        });

        Map<String, Integer> upgradesBefore = upgrades(save.path(UPGRADES));
        if (!upgradesBefore.equals(upgrades)) {
            changes.add(UPGRADES + " " + upgradesBefore + " → " + upgrades);
            ObjectNode object = save.putObject(UPGRADES);
            upgrades.forEach(object::put);
        }

        String shipBefore = save.path(SELECTED_SHIP).asString("");
        if (!shipBefore.equals(selectedShip)) {
            changes.add(SELECTED_SHIP + " '" + shipBefore + "' → '" + selectedShip + "'");
            save.put(SELECTED_SHIP, selectedShip);
        }

        if (changes.isEmpty()) {
            // Nothing written, so the revision does not move either: an unchanged form submitted
            // twice must not make the device throw away local progress for nothing.
            return Result.of(Outcome.UNCHANGED);
        }

        long revision = save.path(REVISION).asLong(0) + 1;
        save.put(REVISION, revision);

        String written = json.writeValueAsString(save);
        if (written.getBytes(StandardCharsets.UTF_8).length > ProgressService.MAX_PROGRESS_BYTES) {
            return Result.invalid("The edited save would exceed 64 KB.");
        }

        row.setProgressData(written);
        progress.saveAndFlush(row);

        audit.progressEdited(actor, playerId, player.displayName(), changes, callerIp);
        log.info("admin '{}' edited the save of player {} (revision {}): {}",
                actor, playerId, revision, changes);

        return new Result(Outcome.SAVED, null, changes);
    }

    // ── parsing ────────────────────────────────────────────────────────────────────────

    private static Long parseCount(String raw, long max) {
        String text = trim(raw);
        if (text.isEmpty() || !text.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            long value = Long.parseLong(text);
            return value <= max ? value : null;
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    /** One id per line (commas also accepted). Order kept, duplicates dropped, blanks ignored. */
    private static List<String> parseIds(String raw) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : trim(raw).split("[\\r\\n,]+")) {
            String id = part.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!ID.matcher(id).matches()) {
                return null;
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static Map<String, Integer> parseUpgrades(String raw) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String line : trim(raw).split("[\\r\\n]+")) {
            String entry = line.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String id = entry.substring(0, eq).trim();
            Long level = parseCount(entry.substring(eq + 1), MAX_UPGRADE_LEVEL);
            if (!ID.matcher(id).matches() || level == null) {
                return null;
            }
            // Level 0 is how the game says "not owned": leaving the key out means the same, and
            // storing it would make an unchanged save look changed next time.
            if (level > 0) {
                levels.put(id, level.intValue());
            }
        }
        return levels;
    }

    // ── reading the save ───────────────────────────────────────────────────────────────

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> {
                String id = node.asString("");
                if (!id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            });
        }
        return ids;
    }

    private static Map<String, Integer> upgrades(JsonNode object) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (object.isObject()) {
            object.forEachEntry((id, level) -> {
                int value = level.asInt(0);
                if (value > 0) {
                    levels.put(id, value);
                }
            });
        }
        return levels;
    }

    private static List<String> upgradeLines(JsonNode object) {
        List<String> lines = new ArrayList<>();
        upgrades(object).forEach((id, level) -> lines.add(id + "=" + level));
        return lines;
    }

    private static String describeListChange(String name, List<String> before, List<String> after) {
        List<String> added = after.stream().filter(id -> !before.contains(id)).toList();
        List<String> removed = before.stream().filter(id -> !after.contains(id)).toList();
        StringBuilder text = new StringBuilder(name);
        if (!added.isEmpty()) {
            text.append(" +").append(added);
        }
        if (!removed.isEmpty()) {
            text.append(" −").append(removed);
        }
        if (added.isEmpty() && removed.isEmpty()) {
            text.append(" reordered");
        }
        return text.toString();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
