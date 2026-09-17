-- V8__admin_session_epoch.sql — ending the other sessions of an account whose password changed.
--
-- Changing a password used to leave every session already signed in with the old one still
-- working. That defeats the most common reason to change it: "somebody else may have my password"
-- is exactly the case where somebody else may also be signed in right now.
--
-- session_epoch is a counter. A sign-in copies the current value into the session; a password
-- change, or an administrator resetting the password, adds one. AdminSessionGuard compares the two
-- on every request and signs out a session holding an older number. The session that made the
-- change is handed the new number at the same moment, so it is the one that survives.
--
-- A counter rather than a timestamp: sessions and rows would otherwise be compared across clocks,
-- and two changes in the same millisecond would look like one.
--
-- DEFAULT 0, and a session that carries no number at all is read as 0 — so sessions open when this
-- deploys are unaffected until the first change to their account.

ALTER TABLE admin_user
    ADD COLUMN session_epoch integer NOT NULL DEFAULT 0;
