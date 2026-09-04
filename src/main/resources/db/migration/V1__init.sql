-- V1__init.sql — SpaceSurvivors backend, initial schema.
-- Flyway applies this once on first startup and records it in flyway_schema_history.
-- Never edit a migration that has already run; add a V2__ file instead.

-- ── users ───────────────────────────────────────────────────────────────────
-- One row per player. Identity only, no passwords: auth happens out of band.
-- For now user_id comes from the X-Dev-User request header; later a real token.
CREATE TABLE users (
    user_id      text        PRIMARY KEY,
    display_name text        NOT NULL,          -- shown on leaderboards
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now()
);

-- ── players ─────────────────────────────────────────────────────────────────
-- The cloud save. The whole client PlayerProfile is stored as ONE json object
-- in `profile` (jsonb = Postgres' binary, indexable JSON type). The client owns
-- that shape; the server stores it as-is and never unpacks it.
--   version = optimistic lock. The client sends the version it last saw on PUT;
--   the UPDATE runs WHERE version = :v. 0 rows changed => someone else wrote
--   first => the API answers 409 Conflict and the client re-pulls / merges.
CREATE TABLE players (
    user_id    text        PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    profile    jsonb       NOT NULL,
    version    integer     NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Keep updated_at honest without trusting the app to set it: a trigger stamps
-- it on every UPDATE.
CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER players_touch_updated_at
    BEFORE UPDATE ON players
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── leaderboard_entries ─────────────────────────────────────────────────────
-- One row per (user, mode) = that player's personal best. POST /v1/scores
-- upserts here only when a run beats the stored best.
--   mode = 'infinite' | 'campaign'
CREATE TABLE leaderboard_entries (
    user_id          text        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    mode             text        NOT NULL,
    survived_seconds real        NOT NULL CHECK (survived_seconds >= 0),
    kills            integer     NOT NULL CHECK (kills >= 0),
    reached_level    integer     NOT NULL CHECK (reached_level >= 1),
    bosses_defeated  integer     NOT NULL CHECK (bosses_defeated >= 0),
    achieved_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, mode)
);

-- Leaderboard reads are "top N by survived_seconds within a mode" — index that.
CREATE INDEX leaderboard_by_mode_seconds
    ON leaderboard_entries (mode, survived_seconds DESC);
