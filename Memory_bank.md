# Memory bank — backend

Decision log for `~/SpaceSurvivors-backend`. Mirrors the Unity repo's own
`Memory_bank.md`, but scoped to the server.

**What belongs here:** decisions and the reasoning behind them — the "why" that the
code itself cannot tell you. Phase log with commit hashes. Open questions.
**What does not:** how the code works (that is `docs/ogrenme-rehberi.md`), the API
shape (`api-contract.md` — to be regenerated), or anything git history already says.

---

## Context

Cloud save + leaderboards for the SpaceSurvivors Unity game (`~/SpaceSurvivors`).
Separate repo, separate deploy. **The game runs fully offline off a local cache; this
service is additive** — if it is down or unreachable, nothing in the game breaks.

Primary goal of this track is **Tarik learning backend development**, not just
shipping the feature. Explanations and the reasoning behind choices matter as much
as the code.

**Stack:** Java 25 · Spring Boot 4.1.1 · Maven · PostgreSQL 18 · IntelliJ IDEA

---

## History — how we got here

An earlier attempt (Gradle + VS Code) was built and then **scrapped on 2026-09-04**:
Tarik had said the backend would be IntelliJ + Maven and that was missed. The Java
source was sound and largely carried over; only the build tool and IDE changed.
That repo was deleted. Current repo starts at `29c80e5`.

---

## Decisions

### D1 — Profile stored as one `jsonb` blob, not normalised columns

`players.profile` holds the entire client `PlayerProfile` as a single JSON object.

**Why:** the Unity client already serialises the profile as one JSON object
(Newtonsoft). Normalising it into columns would mean a DB migration *and* an API
change every time the client bumps `schemaVersion` — and that schema is expected to
keep growing (planned Stats and Profile screens add fields). With `jsonb` the client
owns its own shape and the server never unpacks it.

`jsonb` over `text`: same simplicity, but it validates that the value is real JSON and
leaves the door open to query inside it (`profile->>'wallet'`) without a migration.

**Cost accepted:** the server cannot enforce anything about the profile's contents.
That is fine — it is the player's own save data, and the client is the only writer.

**Dropped from an earlier draft:** eight `GENERATED ALWAYS AS` columns projecting
wallet/kills/etc. out of the blob. They were for support and analytics we do not have
yet; easy to add later in a `V2__`.

### D2 — `JdbcClient`, not JPA

There is no `@Entity` anywhere. See `docs/ogrenme-rehberi.md` §2 for the full
reasoning; in short: the data model is not an object graph, `jsonb` is awkward under
JPA, the two interesting queries (upsert via `ON CONFLICT`, the rank correlated
sub-query) would have been native SQL anyway, and JPA's implicit behaviour (lazy
loading, dirty checking) works against the learning goal.

### D3 — One database role that owns the schema (for now)

`ss_app` / `ss_app_local_dev` owns `schema public`, so it can run DDL and Flyway needs
only one datasource.

**Why:** the first attempt used the "correct" split — an app role with DML only, plus
a separate migrator role for Flyway. It immediately failed (`ss_app` could not create
`flyway_schema_history`) and needed a second datasource block to fix. That is real
production practice but it is one concept too many while learning the basics.

**Debt:** tighten to least privilege before any deploy. Revisit when Azure work starts.

### D4 — Optimistic locking on `players.version`, not database locks

Client sends the version it last read; `UPDATE ... WHERE version = :v`. Zero rows
changed means someone else wrote first → 409 with the server's current profile.

**Why:** two devices on one account is a real scenario, and the failure mode without
this (silent overwrite, lost progress) is exactly the thing cloud save exists to
prevent. Pessimistic locks would mean holding a DB lock across a network round trip
from a mobile client — unacceptable.

**Client's job:** on 409, merge (take the larger of each counter, union the id lists)
and retry. Not written yet — belongs to the Unity phase.

### D5 — Dev auth via `X-Dev-User` header, real auth deferred

`DevAuthFilter` reads the header and puts `userId` on the request. Controllers read it
with `@RequestAttribute`, so they do not know where it came from.

**Why deferred:** Tarik explicitly took Firebase off the plan for now. Building the
endpoints against a stand-in seam means the auth swap later touches exactly one class.

**Why a filter rather than `@RequestHeader` in each controller:** so the swap really is
one class. This is the whole point of the indirection.

⚠️ **This is not authentication.** Anyone can claim to be anyone. Never expose this
build beyond localhost.

### D6 — Leaderboard keeps one row per (user, mode)

`leaderboard_entries` stores the personal best, not a run history. `POST /v1/scores`
upserts only when the run beats the stored best.

**Why:** the game's leaderboard shows best-per-player. Storing every run would mean
unbounded growth plus a `DISTINCT ON` query to display it, for data nothing reads.

**If run history is ever wanted** (a "your last 20 runs" screen), that is a second
table, not a change to this one.

### D7 — Two tiers of input validation

Field shape → bean validation annotations on the DTO (`@PositiveOrZero`, `@Min`) →
**400**. Business plausibility → explicit checks in the service → **422**.

**Why the split:** 400 means "I could not understand this request", 422 means "I
understood it and will not accept it". A negative kill count is malformed. 50 000 kills
in 10 seconds is well-formed and impossible. Different problems, different answers.

**Anti-cheat honesty:** the kill-rate check stops casual garbage, nothing more. Real
protection needs a server-authoritative run, which is out of scope and probably always
will be for this game.

### D8 — Display name will ride along in the profile, not its own endpoint

Players cannot pick a nickname yet; `users.display_name` is generated as
`Pilot-<first 6 of uid>`.

**Plan (not built):** add `displayName` to the client's `PlayerProfile`, and have
`ProfileService.save()` pass it to `UserService.touch()`. No new endpoint, no extra
HTTP call from Unity, no separate conflict handling.

**Cost accepted:** the leaderboard name only refreshes when the profile is saved —
which happens at the end of every run.

**Blocked on:** the Unity side. Building the server half alone cannot be tested.

---

## Gotchas discovered

- **Spring Boot 4 renamed the starters.** `spring-boot-starter-web` → `-webmvc`;
  Flyway is `spring-boot-starter-flyway`; the single `spring-boot-starter-test` is now
  per-module (`-webmvc-test`, `-jdbc-test`, …). Tutorials written for 3.x will not
  match `pom.xml`.
- **Spring Boot 4 ships Jackson 3.** Package moved: `com.fasterxml.jackson.databind`
  → `tools.jackson.databind` (annotations stayed under `com.fasterxml`). Every
  `ObjectMapper` / `JsonNode` import in this repo uses the new namespace.
- **Hibernate Validator localises messages.** Some validation errors come back in
  Turkish, some in English, depending on which message bundle has a translation. The
  field name in the response is the part clients should rely on.
- **`findMe` had to be shaped carefully.** A flat `SELECT count(*) + 1 ... WHERE` over
  a player with no entry returns one row containing 0 → rank 1, which is wrong. Making
  the player's own row the outer `FROM` means no entry → no rows → `Optional.empty()`.
- **Rank tie-break must match the board's `ORDER BY`.** Equal survival time is broken
  by earlier `achieved_at` in both places; if they drift, a player is told "rank 2" but
  appears third in the list.

---

## Phase log

| Phase | Commit | What landed |
|---|---|---|
| F1 | `29c80e5` | Maven/Spring Boot 4 project, Postgres wiring, Flyway `V1__init.sql` (users / players / leaderboard_entries), `GET /health` |
| F2 | `25dad9e` | `DevAuthFilter`, `GET/PUT /v1/profile`, optimistic locking, `ApiException` + handler |
| F3 | `d5e71ba` | `POST/GET /v1/scores`, bean validation, upsert-if-better, rank + board |
| — | `9878350` | `docs/ogrenme-rehberi.md` — Spring Boot learning guide over this codebase |
| — | `8f1c9f7` | this file |
| F5a | game repo `18d0745` | **Unity client connected.** `HttpProfileStore` / `ProfileMerge` / `BackendConfig` / `BackendBootstrap` + an editor settings window, all behind the game's existing `IProfileStore` seam. Verified end to end against this backend: the real save (wallet 481) now round-trips through `players`. |

**Verification approach:** every phase curl-tested end to end against local Postgres
(F2: 6 scenarios, F3: 15), test rows purged afterwards, then walked through in Postman
by Tarik. Automated tests are still just the context-load smoke test — a gap, see below.

---

## Open / next

1. **Postman collection** — the requests exist only as ad-hoc tabs. Worth saving as a
   collection with a `{{baseUrl}}` variable.
2. **Unity integration** — `HttpProfileStore` / `HttpLeaderboardStore` against the
   existing `IProfileStore` / `ILeaderboardStore` seams in the game repo (commit
   `0989f99` there opened them). Offline-first: local cache is the source of truth,
   sync in the background, merge on 409.
3. **Display name** — D8, together with the Unity work.
4. **Real tests.** Only `SpacesurvivorsApplicationTests` (context loads) exists. Worth
   adding `@WebMvcTest` slices for the controllers and plain unit tests for
   `ScoreService`'s rules. Deliberately skipped so far to keep the learning path linear.
5. **Firebase auth** — replaces `DevAuthFilter`. Deferred by Tarik; console setup steps
   are in `docs/firebase-setup.md` (from the scrapped repo — needs rewriting).
6. **Least-privilege DB roles** — D3 debt, before deploy.
7. **Azure deploy** — Postgres Flexible Server + Container App, secrets from Key Vault.

## Local run

```sh
./mvnw spring-boot:run        # needs local Postgres up
curl localhost:8080/health    # {"status":"UP","db":"UP"}
```

DB: `spacesurvivors` on `localhost:5432`, role `ss_app` / `ss_app_local_dev`
(dev-only password). Inspect with DBeaver.
