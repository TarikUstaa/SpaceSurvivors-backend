package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * What the backoffice may do to a player, and under what conditions.
 *
 * <p>All of this lived in {@code AdminController} until it was read back against D11. The
 * deletion in particular had three separate things in a controller method: the rule that a
 * delete must be confirmed by name, the audit write, and the transaction that binds the two.
 * None of them are about HTTP.</p>
 *
 * <p>The distinction that matters is not tidiness. <b>A rule in a controller is a rule that
 * only applies to requests shaped like that one.</b> The confirm-by-name check protected the
 * one form that existed; a second screen, a cleanup job or a CLI would have deleted players
 * without it, and nothing would have pointed that out. Here the rule travels with the
 * operation, and {@code deletePlayer} is not reachable without passing it.</p>
 */
@Service
public class AdminPlayerService {

    private static final Logger log = LoggerFactory.getLogger(AdminPlayerService.class);

    /** What happened, in the caller's terms. A page turns each of these into a sentence. */
    public enum Outcome { DELETED, NAME_DID_NOT_MATCH }

    /**
     * @param displayName carried back because the page names the player in its message, and
     *                    after a deletion there is nowhere left to look it up
     */
    public record Deletion(Outcome outcome, String displayName) {
    }

    /** The list page, with the two numbers printed above it. */
    public record Overview(List<AdminPlayerRow> rows, int total, long withSaves) {
    }

    private final AdminPlayerQueries players;
    private final AdminAudit audit;

    public AdminPlayerService(AdminPlayerQueries players, AdminAudit audit) {
        this.players = players;
        this.audit = audit;
    }

    /**
     * Every player, plus the counts the page shows above them.
     *
     * <p>Counted here rather than in the template or the controller: "how many of these have a
     * cloud save" is a question about the data, and a controller that answers it is a
     * controller that has started to know what the numbers mean.</p>
     */
    @Transactional(readOnly = true)
    public Overview overview() {
        List<AdminPlayerRow> rows = players.listAll();
        return new Overview(rows, rows.size(),
                rows.stream().filter(AdminPlayerRow::hasSave).count());
    }

    @Transactional(readOnly = true)
    public AdminPlayerDetail detail(UUID playerId) {
        return players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));
    }

    /**
     * Delete a player and everything of theirs, if the name they were asked to type matches.
     *
     * <p><b>The confirmation is a rule, not a dialog.</b> A browser confirm() is a suggestion
     * to whoever is at the keyboard; this is a condition on the request. One that arrives
     * without the right name — from a stale tab, a double submit, a script, or a page on
     * another site — deletes nothing, and no amount of clicking elsewhere changes that.</p>
     *
     * <p>The typing is not theatre either. This is the one irreversible action in the
     * backoffice and it is aimed at a row a mis-click could just as easily have chosen; the
     * name is how the person says <em>which</em> row they meant, not merely that they meant
     * one.</p>
     *
     * <p>{@code @Transactional} covers the delete and the audit entry together, so a failure to
     * record it takes the deletion with it. The save and the scores go by the {@code ON DELETE
     * CASCADE} on those foreign keys, not by JPA — see {@link AdminPlayerQueries#deletePlayer}.</p>
     */
    @Transactional
    public Deletion delete(String actor, UUID playerId, String confirmName, String callerIp) {
        AdminPlayerDetail player = detail(playerId);

        if (!player.displayName().equals(confirmName == null ? "" : confirmName.trim())) {
            // Nothing happened, so nothing is audited. A log that records attempts alongside
            // events is one where "deleted" has to be read twice.
            return new Deletion(Outcome.NAME_DID_NOT_MATCH, player.displayName());
        }

        players.deletePlayer(playerId);
        audit.playerDeleted(actor, playerId, player.displayName(), callerIp);

        // Said out loud in the log as well, because "deleted a player" undersells what just
        // happened. The log line is for whoever is tailing the container; admin_audit is the
        // record that outlives it.
        log.warn("admin '{}' deleted player {} ('{}') along with their save and scores",
                actor, playerId, player.displayName());

        return new Deletion(Outcome.DELETED, player.displayName());
    }
}
