package com.tarikusta.spacesurvivors.admin;

import org.springframework.data.repository.Repository;

/**
 * The audit trail, as narrow as it can be made.
 *
 * <p>Extends {@link Repository} — the marker interface that contributes no methods — so this
 * type has exactly two operations and no {@code delete}, {@code deleteAll} or {@code
 * deleteById} anywhere in the application. That is the enforcement: not a rule somebody has
 * to remember, but a method that does not exist to be called. The same reasoning as
 * {@link AdminPlayerQueries}, applied harder, because a log you can edit is a log.</p>
 *
 * <p>There is no read here beyond a count. The page and the CSV export read through
 * {@link AdminAuditSearch}, which holds only a JdbcClient and only ever SELECTs — so the type that
 * can write the trail stays too small to grow a delete by accident.</p>
 */
public interface AdminAuditRepository extends Repository<AdminAuditEntry, Long> {

    AdminAuditEntry save(AdminAuditEntry entry);

    long count();
}
