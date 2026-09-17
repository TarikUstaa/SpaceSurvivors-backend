-- V7__preserve_last_seen.sql — let an operator's change leave "last seen" alone.
--
-- player_profile.updated_at doubles as "last seen" (V1), and the set_updated_at trigger moves it
-- to now() on every UPDATE. That was right while only the player's own sign-ins updated the row.
-- It stops being right the moment something else writes to it: an administrator renaming a player,
-- suspending one, or the retention job clearing old IP addresses would each make that player look
-- active today — and the retention job would do it to exactly the players who have been gone the
-- longest, resetting the clock it measures by.
--
-- A transaction that should not count as the player being seen sets
--
--     SELECT set_config('app.preserve_updated_at', 'on', true);
--
-- first. The third argument makes it local to the transaction, so it cannot leak into the next
-- request that borrows the same pooled connection. current_setting(..., true) answers NULL rather
-- than raising when nothing set it, which is every other transaction.
--
-- The same function backs player_progress's trigger. Nothing sets the flag around a save write, so
-- that trigger behaves exactly as before.

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('app.preserve_updated_at', true) = 'on' THEN
        RETURN NEW;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
