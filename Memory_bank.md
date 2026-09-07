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

## Mentor review — 2026-09-07 (ACT ON THIS NEXT)

Tarik's mentor (25-30 years of Java) reviewed the code and asked for three things.

**1. Use `JpaRepository`, not `JdbcClient`. — DONE** (`8d93a11`, `c672dac`, `e3039ce`). D2 is overturned. The technical
case for `JdbcClient` was real but optimised for the wrong goal: this project exists for
Tarik to learn, and Spring Data JPA is what the industry and the job market mean by
"Spring". It also removes hand-written code that JPA already provides — `@Version` *is*
the optimistic lock we implemented by counting affected rows.

Shape it takes:
```java
@Entity @Table(name = "player_progress")
class PlayerProgress {
    @Id UUID playerId;
    @JdbcTypeCode(SqlTypes.JSON) String progressData;
    @Version int version;
}
interface PlayerProgressRepository extends JpaRepository<PlayerProgress, UUID> { }
```

Two places that need care, not blockers:
- `leaderboard` has a composite key (`player_id, mode`) → `@IdClass` or `@EmbeddedId`.
- The upsert (`ON CONFLICT DO UPDATE`) has no JPA equivalent → keep it as
  `@Query(nativeQuery = true)`. Mixing is normal and worth saying out loud.

The schema did not change; only the repository layer did, one table per commit so the
build stayed green throughout. What it actually taught, beyond the syntax:

- `@Version` starts at **0**, where the hand-written lock started at 1. That is a wire
  contract change; Unity is unaffected (it stores whatever it is told and echoes it back)
  and the Postman collection was updated.
- The service still compares versions itself rather than letting `@Version` raise, because
  a raised `OptimisticLockException` marks the transaction rollback-only — the query that
  fetches the server's copy for the 409 body could not run afterwards. Same shape as D13.
- `@Modifying(flushAutomatically, clearAutomatically)` is what lets a read immediately
  after a native write see the row instead of a cached absence.
- The `updated_at` trigger **cannot be backdated** — it stamps `now()` on every update,
  including one trying to age a row. Testing the touch throttle means disabling the trigger
  around the change. That is the column doing its job.
- Four queries, four techniques: derived (`JpaRepository`'s own), scalar JPQL, JPQL
  constructor projection, native. Mixing is correct, not a compromise.

Repository tests against real Postgres came with it — the gap the review flagged. 88 tests.

**2. "Why is the parameter in a header?"** Because it is a credential, not a parameter:
it applies to every endpoint uniformly, and query strings land in access logs, proxy
logs and browser history. The likely real objection is the `X-` prefix, which RFC 6648
retired in 2012. Move to `Authorization: Device <id>`, which also makes the Firebase swap
a change of scheme rather than of header. **Not done.**

**3. A Postman collection showing the project's flow** — the artefact he actually wants
to see.

## Decisions

*Newest first. Superseded entries are kept — the reasoning is the record.*

### D15 — a catch-all handler needs the framework's handler underneath it (2026-09-07)

`@ExceptionHandler(Exception.class)` sat in front of every exception Spring MVC raises, so
an unknown path answered **500** instead of 404, a wrong verb 500 instead of 405, an
unreadable body 500 instead of 400 — each logged at ERROR, turning any bot probing for
`/wp-admin` into error-log noise. Reproduced, then fixed by extending
`ResponseEntityExceptionHandler`.

**The general rule:** a catch-all is correct only when something above it already handles
the framework's own exceptions properly.

### D16 — reads must not write

`resolveOrCreate` ran on every request, so fetching progress or browsing the leaderboard
created a player row. Three problems at once: `GET` is defined as safe, a write sat on the
hot path of the busiest endpoints, and anyone could grow `player_profile` by inventing
device ids.

Split into `resolve` (readOnly, `Optional`) and `resolveOrCreate`. Only endpoints that
were going to write use the latter — plus `GET /v1/player`, whose entire job is deciding
who you are, which is the one honest place to stamp `first_login_date`.

### D17 — a repository exposes what the domain allows, not what the framework can generate

`LeaderboardEntryRepository` extended `JpaRepository` and therefore handed out
`save(entity)` — which would overwrite a player's best with a worse run and bypass
upsert-if-better entirely, with nothing in the type system objecting. It now extends the
bare `Repository` marker, so only the four declared methods exist.

`PlayerProfileRepository` and `PlayerProgressRepository` keep `JpaRepository`, because they
genuinely use `findById` / `save` / `saveAndFlush`.

### D18 — the API description is public on purpose

springdoc generates it from the controllers, so it cannot drift the way a hand-written spec
does. Writing the test for it immediately found that `/v3/api-docs` answered **401**: the
filter guarded every path, so reading the document that explains which credential to send
required already knowing it. Docs and probes are now exempt.

Nothing secret is published — the document lists endpoints any client already knows. A
deployment that would rather not publish it should switch springdoc off
(`springdoc.api-docs.enabled=false`), not hide it behind the credential it exists to explain.

### D13 — first contact must not rely on a caught constraint violation (2026-09-07)

A review found that eight concurrent requests for one *new* device returned a 200 and
seven 500s. `PlayerService.create()` caught `DuplicateKeyException` and re-read the
device to see who won the race — but `resolveOrCreate` is `@Transactional`, and in
Postgres **a raised constraint violation aborts the whole transaction**: every later
statement fails with `25P02`, including the recovery query and the retry. The race
handling and the name-retry loop were both dead code, invisible in single-user testing
because collisions are rare.

`insertIfFree` now uses `ON CONFLICT DO NOTHING`, which covers both unique constraints
and returns zero rows rather than raising. Nothing aborts, so the caller can genuinely
distinguish "another request registered this device" (adopt it) from "that name is
taken" (retry).

**The general rule:** inside a transaction, a constraint violation is not a recoverable
error unless you never let it be raised. `applyName` still catches one — safe only
because nothing runs after it.

### D14 — client headers are input, not facts

`X-Forwarded-For` was read unconditionally and passed to `CAST(:ip AS inet)`. Two
consequences, both reproduced: a non-IP value turned every endpoint into a 500 (one
header, no authentication needed), and a valid-looking one was stored verbatim, so
`last_ip` was whatever the caller felt like claiming.

The filter now parses the value and yields null unless it is a literal address, and only
looks at the header when `app.trust-forwarded-for` is enabled — that header carries
meaning only when a proxy we control added it. Off by default; turn it on when something
trustworthy sits in front.

### D9 — player_profile + player_progress, replacing users + players (2026-09-07)

Tarik's call: `users` and `players` were 1:1 on the same key, which bought nothing.
Merged and renamed:

- `player_profile` — identity: `player_id uuid` PK, `device_id` UNIQUE, `display_name`,
  `country`, `last_ip`, `first_login_date`, `updated_at`.
- `player_progress` — the save: `player_id` PK, `progress_data jsonb`, `version`, `updated_at`.
- `leaderboard` — unchanged apart from keying on `player_id`. (Renamed from
  `leaderboard_entries`, and the Java package/classes from `score`/`Score*` to
  `leaderboard`/`Leaderboard*`, so table, package, classes and endpoint all say the same
  word. The endpoint moved `/v1/scores` -> `/v1/leaderboard` with them.)

**player_id is deliberately not device_id.** The device id is *how we recognise* a player
and can change (reinstall, new phone); the player id is *who they are* and never changes.
Keeping them apart is what will later let real auth attach several devices to one player
without touching a single row of progress. Costs nothing now, is very expensive to retrofit.

uuid rather than a sequence so the row count is not public.

`device_id` was deliberately NOT duplicated onto `player_progress`: a second copy can drift
from the first, and `player_id` already joins the two.

**Schema rebuilt in place rather than migrated.** V1 was days old, the data was throwaway,
and a "V1 creates users/players, V2 immediately drops them" history would be noise. Tables
dropped, V1 rewritten, Flyway rebuilt from the file. In a shipped system this would have
been a V2 — an applied migration is never edited.

### D10 — display names are unique, case-insensitively

`UNIQUE INDEX ON player_profile (lower(display_name))`. A plain UNIQUE would let `tarik`
and `TARIK` coexist, which makes impersonation trivial.

Uniqueness forced the generator to change: `User` + 4 digits gives only 10 000 names, so
with a unique index it exhausts almost immediately. Now `User100000`..`User999999` — always
six digits, 900k to draw from, random rather than sequential so it does not leak how many
players exist. `PlayerService.create` retries on collision; if a device row appeared
meanwhile, another request for the same device won the race and we adopt its player.

Name rules (3-16 chars, `[A-Za-z0-9_]`) live in `PlayerService`, not as annotations on the
request record, because the generated default has to satisfy them too. They are also a CHECK
constraint, so no code path can write a name the rules forbid.

### D11 — controllers hold no logic

Tarik's review: nothing that belongs in a service may sit in a controller. `ScoreController`
was already clean; `ProfileController` was not — it validated the body, built responses out
of `Map.of`, and branched on the outcome inline.

Now every endpoint is one delegating line, except `ProgressController.save`, which switches
over a sealed `SaveOutcome` to pick 200 vs 409. That is HTTP mapping, not a rule, and the
sealed type means the compiler rejects a forgotten case. Ad-hoc maps are gone: every response
is a named record, so the shape is a checkable contract rather than a string key.

`HealthController` used `JdbcClient` directly, which V1-era notes defended as "one query, three
classes would be silly". The rule is stated plainly now, so consistency wins: `HealthService`
owns the check and also swallows a DB failure into `"db":"DOWN"` — a health endpoint must
answer, not throw.

### D12 — append-only run history deliberately deferred

An append-only `player_run` table (one row per finished run) was proposed and declined for
now. It is the one thing here that cannot be added retroactively: `player_progress` is
overwritten on every save and `leaderboard` keeps only the best, so every run played
before that table exists is gone.

**Cost of the delay:** a Stats screen, a "last 20 runs" view, run-distribution anti-cheat and
any analytics will all start from the day the table lands, with no history behind them.
Accepted knowingly.

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

> **OVERTURNED by the mentor review, 2026-09-07.** All three tables are JPA entities now.
> The reasoning below was sound in isolation but optimised for the wrong goal: this project
> exists for Tarik to learn, and Spring Data JPA is what the industry means by "Spring".
> It also removed hand-written code JPA already provides — `@Version` *was* the optimistic
> lock implemented by counting affected rows. Kept as the record of a decision and why it
> was wrong.

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

> *Still true; the column is now `player_progress.version` (D9).*

Client sends the version it last read; `UPDATE ... WHERE version = :v`. Zero rows
changed means someone else wrote first → 409 with the server's current profile.

**Why:** two devices on one account is a real scenario, and the failure mode without
this (silent overwrite, lost progress) is exactly the thing cloud save exists to
prevent. Pessimistic locks would mean holding a DB lock across a network round trip
from a mobile client — unacceptable.

**Client's job:** on 409, merge (take the larger of each counter, union the id lists)
and retry. Not written yet — belongs to the Unity phase.

### D5 — Dev auth via `X-Dev-User` header, real auth deferred

> *Header is now `X-Device-Id` and the filter is `DeviceAuthFilter` (D9). Everything below still holds.*

`DevAuthFilter` reads the header and puts `userId` on the request. Controllers read it
with `@RequestAttribute`, so they do not know where it came from.

**Why deferred:** Tarik explicitly took Firebase off the plan for now. Building the
endpoints against a stand-in seam means the auth swap later touches exactly one class.

**Why a filter rather than `@RequestHeader` in each controller:** so the swap really is
one class. This is the whole point of the indirection.

⚠️ **This is not authentication.** Anyone can claim to be anyone. Never expose this
build beyond localhost.

### D6 — Leaderboard keeps one row per (user, mode)

> *Now keyed on `player_id`, and the table is `leaderboard` (D9). See also D12 on the run history this rules out.*

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

> **Superseded by D9/D10.** `display_name` is a real column now and the player edits it
> deliberately, so it got its own endpoint (`PATCH /v1/player`) rather than riding in the
> save blob — the name should not depend on a run ending to take effect.

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
| F5b | game repo `fa83b63` | **Leaderboard connected.** `HttpLeaderboardStore` posts finished runs to `/v1/scores`. Verified: upsert-if-better replaces the single row in place. `GET /v1/scores` still has no client — nothing in the game displays a board yet. |
| F6 | (this change) | **Schema and layering rework** — see D9-D12. `users`+`players` became `player_profile`+`player_progress`; `player_id uuid` split from `device_id`; unique case-insensitive display names with `PATCH /v1/player`; `DevAuthFilter` became `DeviceAuthFilter` carrying a `Caller`; controllers reduced to delegation; `HealthService` added; endpoint renamed `/v1/profile` -> `/v1/progress`. DB dropped and rebuilt from the rewritten V1. Unity client updated (header, URL, wire field `profile` -> `progress`, full-GUID device id). 15 curl scenarios green. |

**Verification approach:** every phase curl-tested end to end against local Postgres
(F2: 6 scenarios, F3: 15), test rows purged afterwards, then walked through in Postman
by Tarik. Automated tests are still just the context-load smoke test — a gap, see below.

---

## Open / next

1. **In-game profile screen** — nothing calls `PATCH /v1/player` yet, so every player keeps
   their generated `UserNNNNNN`. The endpoint and its 400/409 answers are ready for it.
2. **`country` has no source.** The column exists and stays NULL until a host or CDN supplies
   a country header. Locally `last_ip` is always `::1`, so nothing can be derived from it.
3. **IP is personal data.** Stored deliberately; it will need a purpose and a retention rule
   (a "delete IPs older than N days" job) before this is public.
4. **Postman collection** — the requests exist only as ad-hoc tabs. Worth saving as a
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
