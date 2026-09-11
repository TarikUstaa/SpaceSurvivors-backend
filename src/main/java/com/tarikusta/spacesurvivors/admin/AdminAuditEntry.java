package com.tarikusta.spacesurvivors.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One line of the audit trail.
 *
 * <p><b>Write-once.</b> There is no setter on this class and the fields are assigned in the
 * constructor, so an entry cannot be edited after it is created — not by a mistake, not by a
 * later feature, not by Hibernate flushing a change somebody made to a managed instance.
 * That is most of what "append-only" means at this layer; {@link AdminAuditRepository} covers
 * the rest by not offering a delete.</p>
 *
 * <p>V4__admin_audit.sql explains why the actor and the target are text rather than foreign
 * keys — briefly: the log has to outlive the rows it describes.</p>
 */
@Entity
@Table(name = "admin_audit")
public class AdminAuditEntry {

    /**
     * {@code IDENTITY}, matching the {@code bigserial} column: the database allocates the
     * number. The alternative Hibernate would otherwise pick is a sequence it manages in
     * batches, which hands out ids in advance and can leave them unused — fine for an entity,
     * wrong for a log whose gaps are supposed to mean something.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id", updatable = false)
    private Long auditId;

    @Column(name = "happened_at", insertable = false, updatable = false)
    private Instant happenedAt;

    @Column(nullable = false, updatable = false)
    private String actor;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}, and in an audit table the difference is
     * not a preference. Ordinal stores the enum's position, so reordering the constants — or
     * inserting one in the middle — silently reinterprets every row already written. History
     * would change because somebody tidied a Java file.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AdminAction action;

    @Column(updatable = false)
    private String target;

    @Column(nullable = false, updatable = false)
    private String summary;

    @Column(name = "actor_ip", updatable = false)
    private String actorIp;

    protected AdminAuditEntry() {
        // JPA needs a no-arg constructor.
    }

    AdminAuditEntry(String actor, AdminAction action, String target, String summary,
                    String actorIp) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.summary = summary;
        this.actorIp = actorIp;
    }

    public Long getAuditId() {
        return auditId;
    }

    public Instant getHappenedAt() {
        return happenedAt;
    }

    public String getActor() {
        return actor;
    }

    public AdminAction getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getSummary() {
        return summary;
    }

    public String getActorIp() {
        return actorIp;
    }

    /**
     * Formatted here rather than in the template, for the reason spelled out in
     * {@link AdminPlayerRow}: an {@link Instant} carries no calendar, so asking Thymeleaf's
     * {@code #temporals.format} for {@code yyyy-MM-dd} throws at render time — on a page that
     * compiled and deployed perfectly well. UTC because that is what the column holds.
     */
    private static final DateTimeFormatter SECOND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneOffset.UTC).withLocale(Locale.ROOT);

    public String when() {
        return happenedAt == null ? "—" : SECOND.format(happenedAt);
    }

    /**
     * The action as it appears on the page: {@code PLAYER_DELETED} reads as "player deleted".
     *
     * <p>{@code Locale.ROOT} because this lowercases an identifier, not somebody's prose.
     * {@code toLowerCase()} would use the JVM's default locale, and in Turkish {@code 'I'}
     * lowercases to the dotless {@code 'ı'} — the audit page would read "sıgned ın" on a
     * machine set to Turkish and "signed in" in the container, from the same code.</p>
     */
    public String label() {
        return action == null ? "" : action.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
