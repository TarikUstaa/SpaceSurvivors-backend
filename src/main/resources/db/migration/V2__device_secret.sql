-- V2__device_secret.sql — give each device a credential the server can actually verify.
--
-- Until now the device id was both the claim and the proof: whoever sent one was believed.
-- That made it a password with none of a password's protections — stored in the clear,
-- sent on every request, never expiring, impossible to revoke.
--
-- The device now also holds a secret. Only its BCrypt hash is stored here, so a copy of
-- this table proves nothing: BCrypt is deliberately slow and salted per row, which is what
-- makes a stolen table impractical to attack offline.
--
-- Nullable on purpose. Rows created before this migration have no secret yet, and the
-- token endpoint treats the first authentication of such a device as the moment to set
-- one. Making it NOT NULL would have locked those players out of their own progress.
ALTER TABLE player_profile
    ADD COLUMN device_secret_hash text;

COMMENT ON COLUMN player_profile.device_secret_hash IS
    'BCrypt hash of the device secret. NULL means the device predates V2 and will have '
    'its secret set on next authentication.';
