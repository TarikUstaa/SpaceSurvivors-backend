-- V5__admin_roles.sql — more than one kind of backoffice operator.
--
-- Until now every admin_user row was ADMIN, because there was only ever one row. The role
-- column existed from V3, but nothing constrained it: any string was a valid role, and a typo
-- ("ADMN", "support ") would have produced an account that signs in and is allowed nowhere —
-- or, worse, one whose authority is decided by whatever the code happens to compare it to.
--
-- Two roles, and the difference between them is what a mistake costs:
--
--   ADMIN    everything, including the irreversible things (deleting a player), the record of
--            what everybody did (the audit trail), and who may sign in at all (the users page).
--
--   SUPPORT  the day-to-day work: read players and the leaderboard, edit a player's save,
--            remove a score. Not the irreversible things, not the audit trail, not accounts.
--
-- The list is enforced here as well as in the Java enum, because the enum only governs rows
-- written by this application. A row typed into psql goes through this CHECK and nothing else.

ALTER TABLE admin_user
    ADD CONSTRAINT admin_user_role_check CHECK (role IN ('ADMIN', 'SUPPORT'));

-- An account created by somebody else starts with a password somebody else has seen. Until the
-- owner replaces it, the person who created the account can sign in as them — which makes the
-- audit trail's "actor" column a claim rather than a fact.
--
-- The flag is set when an administrator creates an account or resets its password, and cleared
-- only when the owner chooses their own. While it is set, every backoffice page redirects to the
-- password form (AdminSessionGuard).
--
-- DEFAULT false so the existing administrator, who chose their own password, is untouched.
ALTER TABLE admin_user
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;
