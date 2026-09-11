-- V4__admin_audit.sql — what the backoffice did, in a table instead of a log line.
--
-- Up to now every administrative action said what it had done with log.info/log.warn, and
-- that was better than silence but not by much. Container logs on this deployment scale to
-- zero with the container, live in Log Analytics rather than in the application, and are
-- queried with a language nobody here writes. "Who deleted that player?" had an answer in
-- principle and no answer in practice.
--
-- The backoffice can now permanently delete a person's save and their scores. An action that
-- cannot be undone should at least be one that cannot be denied.

CREATE TABLE admin_audit (
    -- bigserial rather than uuid, and the choice carries meaning here. This is a log: the
    -- rows have a true order, and a monotonic key records it even when two rows share a
    -- timestamp to the microsecond. It also makes tampering visible — a uuid key can lose a
    -- row without trace, while a gap in this sequence is a question somebody has to answer.
    audit_id    bigserial   PRIMARY KEY,

    happened_at timestamptz NOT NULL DEFAULT now(),

    -- The administrator's username, copied in as TEXT — deliberately not a foreign key to
    -- admin_user. A log that points at accounts stops being readable the moment an account
    -- is deleted or renamed, and the record of what somebody did must outlive their access.
    -- For a failed sign-in this holds the username that was tried, which may be nobody.
    actor       text        NOT NULL,

    -- A closed set, written by AdminAction. Free-form strings would mean every call site
    -- invents its own wording and the table becomes unsearchable within a month.
    action      text        NOT NULL,

    -- What was acted on, usually a player_id. Also TEXT and also not a foreign key, and here
    -- the reason is sharper: the most important thing this table records is a player being
    -- deleted. A foreign key with ON DELETE CASCADE would erase that row along with them —
    -- the log would delete its own evidence — and one without a cascade would refuse the
    -- delete entirely. Neither is what a log is for.
    target      text,

    -- The sentence a person reads. Written at the moment it happened, so it keeps names and
    -- details that no longer exist anywhere else once the row they describe is gone.
    summary     text        NOT NULL,

    -- Where the request came from, by the same rules as player_profile.last_ip: getRemoteAddr
    -- only, never X-Forwarded-For. Nearly worthless while there is one administrator working
    -- from home — except on SIGN_IN_FAILED, where it is the only thing that identifies
    -- whoever is trying, and that is the row this column exists for.
    actor_ip    text
);

-- Every read of this table is "the most recent N", so the index matches the only query.
CREATE INDEX admin_audit_recent ON admin_audit (happened_at DESC, audit_id DESC);

-- Append-only is a property of the code, not yet of the database: AdminAuditRepository
-- exposes save() and a listing and nothing else, so no UPDATE or DELETE against this table
-- can be written through the application. The stronger version is a REVOKE UPDATE, DELETE ON
-- admin_audit FROM ss_app in deploy/db-roles.sql, which would hold even against a hand-typed
-- statement. Left out for now because db-roles.sql runs before the schema exists on a first
-- setup; it is the next step if this table ever matters to anyone but its author.

COMMENT ON TABLE admin_audit IS
    'Append-only record of backoffice actions. Actor and target are text, not foreign keys, '
    'so the record survives the deletion of whoever and whatever it describes.';
