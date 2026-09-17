-- V11__player_suspension.sql — taking a player out of the game without deleting them.
--
-- Deleting is the wrong tool for a cheater or an abusive name: it destroys the evidence, and the
-- next launch of the same device simply registers a fresh player with a clean slate. Suspending
-- keeps the account, its save and its scores, and refuses it:
--
--   • sign-in (POST /v1/auth/token) answers 403 with the reason, so the game can say why;
--   • a leaderboard submission is refused;
--   • its scores disappear from the public board and from everyone else's rank.
--
-- A token issued before the suspension keeps working until it expires (an hour, app.jwt.ttl) for
-- what it already had open. That is the honest limit of stateless tokens; the board and new
-- submissions are closed immediately regardless.
--
-- Reversible, both roles may do it, and it is audited with the reason — moderation, not deletion.

ALTER TABLE player_profile
    ADD COLUMN suspended_at      timestamptz,
    ADD COLUMN suspension_reason text CHECK (char_length(suspension_reason) <= 280);
