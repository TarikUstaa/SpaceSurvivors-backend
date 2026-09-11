package com.tarikusta.spacesurvivors.admin;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * The audit trail, as narrow as it can be made.
 *
 * <p>Extends {@link Repository} — the marker interface that contributes no methods — so this
 * type has exactly two operations and no {@code delete}, {@code deleteAll} or {@code
 * deleteById} anywhere in the application. That is the enforcement: not a rule somebody has
 * to remember, but a method that does not exist to be called. The same reasoning as
 * {@link AdminPlayerQueries}, applied harder, because a log you can edit is a log.</p>
 *
 * <p>There is no {@code findById} either. Nothing needs one entry; the page wants the recent
 * ones in order, and that is the whole read side.</p>
 */
public interface AdminAuditRepository extends Repository<AdminAuditEntry, Long> {

    AdminAuditEntry save(AdminAuditEntry entry);

    /**
     * The most recent entries, newest first.
     *
     * <p>Ordered by the key as well as the time. Two rows written in the same transaction can
     * share {@code happened_at} to the microsecond — {@code now()} is the transaction's start
     * time in Postgres, not the statement's — and without the second term their order on the
     * page would be whatever the planner felt like.</p>
     *
     * <p>{@link Limit} rather than a page: this screen is "what happened lately", and the
     * honest shape for that is a capped list with a line saying it is capped. Real pagination
     * is the same open item the player list has, and it should arrive for both at once.</p>
     */
    List<AdminAuditEntry> findAllByOrderByHappenedAtDescAuditIdDesc(Limit limit);

    long count();
}
