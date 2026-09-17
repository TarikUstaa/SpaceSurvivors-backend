-- V10__app_setting.sql — values an operator changes while the game is running.
--
-- The first two are the announcement shown on the main menu and the remote game settings (curse
-- chance, enemy health scale, …). Both are a small JSON document that is replaced whole, read far
-- more often than written, and never queried by its contents — which is a key and a value, not a
-- table per setting. A column per setting would mean a migration every time a tunable is added;
-- the list of what may be set lives in code (game/GameTunable), where a new entry also brings the
-- bounds that make it safe to change remotely.
--
-- updated_by is the backoffice username, as text, for the same reason admin_audit.actor is: the
-- record must not depend on the account still existing. The audit trail has the full history; this
-- column only answers "who set what is live right now" at a glance.

CREATE TABLE app_setting (
    key        text        PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text        NOT NULL
);
