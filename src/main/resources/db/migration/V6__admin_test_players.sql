-- V6__admin_test_players.sql — players an administrator made up.
--
-- The backoffice can now create a player and set their scores, so that the leaderboard and the
-- player list can be tested with more than the one real account that exists. Those rows are real
-- rows — the game's public board shows them — and the day the game has real players, somebody will
-- need to find and remove every one of them. This column is how.
--
-- A column and not a naming convention ("Test_..."): a name can be changed by anyone who holds the
-- account, and a convention is a thing people forget to follow. The flag is written once, by the
-- one statement that creates such a player, and nothing updates it.
--
-- DEFAULT false, so every player that already exists — all created by a real device — is correct
-- without a backfill.
--
-- Such a player also has a device_secret_hash nobody knows (see AdminPlayerService). Leaving it
-- NULL would be the dangerous choice: V2 treats NULL as "a device from before secrets existed" and
-- lets the next caller presenting that device id set one — which would hand the account to them.

ALTER TABLE player_profile
    ADD COLUMN created_by_admin boolean NOT NULL DEFAULT false;
