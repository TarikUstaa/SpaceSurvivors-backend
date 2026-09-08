# Memory bank — backend

Decision log for `~/SpaceSurvivors-backend`. Mirrors the Unity repo's own
`Memory_bank.md`, but scoped to the server.

**What belongs here:** decisions and the reasoning behind them — the "why" that the
code itself cannot tell you. Phase log with commit hashes. Open questions.
**What does not:** how the code works (that is `docs/ogrenme-rehberi.md`), the API
shape (springdoc serves it live at `/v3/api-docs` and `/swagger-ui.html`), or anything
git history already says.

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

## Current state — 2026-09-08

Read this first. Everything below is built, tested and pushed.

**Learning arrangement:** a separate AI assistant is walking Tarik through this codebase
for the learning goal; another (this one) does the development. **This file plus
`docs/ogrenme-rehberi.md` and `docs/savunma-notlari.md` are the shared source of truth** —
keep them current so the teaching side is never working from a stale picture.

- **On GitHub, private:** `github.com/TarikUstaa/SpaceSurvivors-backend`, branch `main`,
  every commit authored `tusta <tarikusta09@gmail.com>`. Sibling game repo:
  `github.com/TarikUstaa/SpaceSurvivors`. First push 2026-09-08; history was rewritten to
  the one author and the filter-branch backups pruned.
- **Endpoints, all live, all behind a JWT (`Authorization: Bearer <token>`) except the
  public ones:** `POST /v1/auth/token` (public) · `GET|PUT /v1/progress` ·
  `GET|PATCH /v1/player` · `GET|POST /v1/leaderboard` · `GET /health` (public) ·
  `/v3/api-docs` + `/swagger-ui.html` (public).
- **Persistence:** all three tables are JPA `@Entity`. Flyway `V1` (schema) + `V2`
  (`device_secret_hash`). `spring.jpa.hibernate.ddl-auto=validate` — Flyway owns the schema,
  Hibernate only checks the entities match.
- **Auth:** device secret (BCrypt hash in `player_profile.device_secret_hash`) exchanged at
  `POST /v1/auth/token` for a 1-hour HS256 JWT whose subject is `player_id`. Spring Security
  resource server verifies the signature before any controller runs. `@CurrentPlayer UUID`
  gives a controller the caller.
- **Tests:** `./mvnw test` → **88 green** across 11 classes — unit, `@WebMvcTest` slices,
  real-Postgres repository tests, and 3 integration tests. Bound to the local DB
  (no Testcontainers yet).
- **Postman:** `docs/SpaceSurvivors.postman_collection.json` — 6 resource folders,
  30 requests, 56 assertions, self-verifying and re-runnable (`runId`-derived device ids).
  `newman run` green; it caught D20.
- **Runs from IntelliJ** (`SpacesurvivorsApplication` → Run), never `./mvnw spring-boot:run` —
  a terminal server holds port 8080 and breaks Tarik's Run.

### The immediate priority

**Rate limiting on `POST /v1/auth/token`.** It runs BCrypt (deliberately ~100 ms) on every
call with no ceiling — a denial-of-service lever, flagged in D19. Plan discussed, not
started: Bucket4j, per-IP token bucket, a servlet filter ordered *before* Spring Security so
a rejected request never reaches BCrypt; `429` + `Retry-After` + `ProblemDetail`. Scope is
the auth endpoint only for now; a general per-IP limit can follow in the same filter.

---

## History — how we got here

An earlier attempt (Gradle + VS Code) was built and then **scrapped on 2026-09-04**:
Tarik had said the backend would be IntelliJ + Maven and that was missed. The Java
source was sound and largely carried over; only the build tool and IDE changed.
That repo was deleted. Current repo starts at `d1f930b`.

---

## Mentor review — 2026-09-07 (all three items done)

Tarik's mentor (25-30 years of Java) reviewed the code and asked for three things.
All three landed; kept here because the reasoning is the record.

**1. Use `JpaRepository`, not `JdbcClient`. — DONE** (`3386cd0`, `500a763`, `09731e8`). D2 is overturned. The technical
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

**2. "Why is the parameter in a header?" — DONE** (`27747ef`, then absorbed by D19).
Because it is a credential, not a parameter: it applies to every endpoint uniformly, and
query strings land in access logs, proxy logs and browser history. The real objection was
the `X-` prefix, which RFC 6648 retired in 2012. It moved to `Authorization: Device <id>`,
then D19 replaced the whole scheme with `Authorization: Bearer <jwt>` — so the header is now
exactly what every OAuth2 client already expects, and a Firebase swap later is `Device`/our
JWT → Firebase's `Bearer`.

**3. A Postman collection showing the project's flow — DONE** (`27747ef` first cut,
`c48c22d` reorganised by resource and made self-verifying). 30 requests, 56 assertions,
re-runnable. It immediately earned its keep by catching D20.

## Decisions

*Newest first. Superseded entries are kept — the reasoning is the record.*

### D20 — an inet column must be read-only to JPA (2026-09-07)

Every `PATCH /v1/player` answered **503**. `last_ip` is Postgres' `inet`; Hibernate binds a
`String` field as `varchar`, and Postgres refuses to assign varchar to inet:

```
ERROR: column "last_ip" is of type inet but expression is of type character varying
```

So *any* update Hibernate wrote for `PlayerProfile` failed, whatever it was actually
changing — renaming was simply the first path to try one. `columnDefinition = "inet"` does
not help; it only shapes generated DDL, and Flyway owns the schema.

Fixed by marking the field `insertable = false, updatable = false`. Nothing is lost: JPA
never had a reason to write it. The address is set by `insertIfFree` and refreshed by
`touch`, both of which cast it explicitly in SQL.

**How it was missed, and the lesson.** Every test of the rename path mocked the repository,
so the UPDATE Hibernate actually emits had never once reached Postgres. Mocks confirm that
the code calls what we expect; only a real database says whether the call works. This is the
second time an integration test found something no unit test could — the first was the
`updated_at` trigger refusing to be backdated.

It was found by the Postman collection's own assertions, which is the argument for making a
collection self-verifying rather than a list of requests to eyeball.

### D19 — real authentication: device secret + signed token (2026-09-07)

**D5 is fulfilled.** The device id was a claim nobody checked; anyone who learned one became
that player. It behaved like a password with none of a password's protections: stored in the
clear, sent on every request, never expiring, impossible to revoke.

Chosen over Firebase deliberately. Firebase would have bought the same protection with less
Java, plus a path to real accounts later — but Spring Security was the largest remaining gap
in what this project teaches, and `player_id` stays stable either way, so adding Firebase
later is still cheap.

**Shape.** `V2` adds `device_secret_hash`; only the BCrypt hash is stored, so a copy of the
table proves nothing. Nullable, because rows written before V2 have no secret — those devices
adopt the one they present rather than being locked out of progress they already earned.
`POST /v1/auth/token` exchanges deviceId + deviceSecret for a one-hour JWT. Spring Security
verifies the signature before a request reaches a controller.

**Every failure gives the same answer.** Distinguishing "no such device" from "wrong secret"
hands over account enumeration one request at a time.

**The unexpected win.** The token carries `player_id` as its subject, so every request already
knows who is calling. The device-to-player lookup that ran on all of them is gone;
`ProgressService` and `LeaderboardService` no longer depend on `PlayerService` at all, and
"last seen" is written once at login instead of on the hot path of every read.

**Known limits, stated plainly:**
- The secret sits in PlayerPrefs. Someone with the unlocked device has the account — the
  honest ceiling of device-based identity, and why real accounts (Firebase, email) remain the
  answer to "I lost my phone".
- `POST /v1/auth/token` runs BCrypt on every call, which is intentionally slow. That is a
  denial-of-service lever until rate limiting exists.
- A never-seen device id can still be claimed by whoever registers it first. Device ids are
  random GUIDs, so this is theoretical, but it is the model.

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

> **Fully superseded by D19.** Real authentication exists now: signed JWT, Spring Security,
> BCrypt device secret. `DevAuthFilter`/`DeviceAuthFilter` and the `Caller` record were
> deleted. The "never expose beyond localhost" warning below no longer applies. Kept for the
> record of why the seam was built the way it was — the swap did land in roughly one place.

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
| F1 | `d1f930b` | Maven/Spring Boot 4 project, Postgres wiring, Flyway `V1__init.sql` (users / players / leaderboard_entries), `GET /health` |
| F2 | `0c742cd` | `DevAuthFilter`, `GET/PUT /v1/profile`, optimistic locking, `ApiException` + handler |
| F3 | `da6f750` | `POST/GET /v1/scores`, bean validation, upsert-if-better, rank + board |
| — | `dd2506c` | `docs/ogrenme-rehberi.md` — Spring Boot learning guide over this codebase |
| — | `1eef668` | this file |
| F5a | game repo `73e0ae1` | **Unity client connected.** `HttpProfileStore` / `ProfileMerge` / `BackendConfig` / `BackendBootstrap` + an editor settings window, all behind the game's existing `IProfileStore` seam. Verified end to end against this backend: the real save (wallet 481) now round-trips through `players`. |
| F5b | game repo `6fe1eac` | **Leaderboard connected.** `HttpLeaderboardStore` posts finished runs to `/v1/scores`. Verified: upsert-if-better replaces the single row in place. `GET /v1/scores` still has no client — nothing in the game displays a board yet. |
| F6 | `c2debf8` … `5e1a62d` | **Schema and layering rework** — see D9-D12. `users`+`players` became `player_profile`+`player_progress`; `player_id uuid` split from `device_id`; unique case-insensitive display names with `PATCH /v1/player`; `DevAuthFilter` became `DeviceAuthFilter` carrying a `Caller`; controllers reduced to delegation; `HealthService` added; endpoint renamed `/v1/profile` -> `/v1/progress`. DB dropped and rebuilt from the rewritten V1. Unity client updated. 15 curl scenarios green. |
| F7 | `4c8c907`, `0cd192e`, `582421a`, `8ecda15`, `df4ecae` | **Code review, round 1** — D13/D14. Three reproduced bugs fixed (concurrent first contact, `X-Forwarded-For`). HTTP types taken out of the services; controller response-building moved into DTOs; requests with no device id refused; the first real test suite added — the concurrency bug would have been caught by one `PlayerService` test. |
| F8 | `13ebf47`, `6f70c66`, `27747ef`, `211455a` | **Mentor review recorded + acted on** — `docs/savunma-notlari.md` written; identity moved to `Authorization: Device <id>` (mentor item 2); first Postman collection. |
| F9 | `3386cd0`, `500a763`, `09731e8`, `2519f4c` | **JPA migration** (mentor item 1). All three tables became `@Entity`, one table per commit so the build stayed green. Real-Postgres repository tests came with it. Schema untouched. See the mentor-review section for what it taught. |
| F10 | `8bd461c`, `911e9bf`, `d47120b`, `fab803a` | **Code review, round 2** — D15-D18. `ResponseEntityExceptionHandler` under the catch-all; reads that were writing; a repository typed wider than the domain allows; `/v3/api-docs` was behind the credential it explains. |
| F11 | `0ba4509`, `5935f34` | **Real authentication** — D19. `V2` adds `device_secret_hash`; Spring Security resource server; HS256 JWT with `player_id` as subject, which deleted the per-request device lookup entirely. `Authorization: Bearer`. Unity got `BackendSession` (token exchange, retries once on 401). |
| F12 | `c48c22d`, `a1634c1` | Postman collection reorganised by resource and made self-verifying — it caught D20 (`inet` column broke every `PlayerProfile` update) on its first run. |
| — | game `8655397` · backend `a1634c1` | **Both repos pushed to GitHub** (private). Authorship rewritten to the one author across all commits; filter-branch backups pruned after the push verified. |

**Verification approach:** `./mvnw test` (88 cases, all layers) is the automated net;
`docs/SpaceSurvivors.postman_collection.json` run with `newman` is the end-to-end
regression suite (56 assertions). Every phase was also walked through in Postman by Tarik.
Twice an integration test found what a mocked one structurally could not (D20, and the
`updated_at` trigger).

---

## Open / next

*Priority order.*

1. **Rate limiting** — the one open security item. See "Current state" above for the plan.
2. **Testcontainers** — the repository and integration tests need a real Postgres and use
   the local one, so they will not run in CI or on a fresh clone. `spring-boot-testcontainers`
   + a `@ServiceConnection` Postgres container.
3. **In-game profile screen** (Unity) — nothing calls `PATCH /v1/player`, so every player
   keeps their generated `UserNNNNNN`. The endpoint and its 400/409 answers are ready.
4. **Leaderboard screen** (Unity) — `GET /v1/leaderboard` has no client; nothing displays a
   board.
5. **`country` has no source.** The column stays NULL until a host or CDN supplies a country
   header. Locally `last_ip` is always `::1`.
6. **IP retention.** `last_ip` is personal data, stored deliberately; it needs a purpose and
   a "delete IPs older than N days" job before this is public.
7. **Least-privilege DB roles** — D3 debt, before any deploy.
8. **Azure deploy** — Postgres Flexible Server + Container App, secrets from Key Vault.
9. **Firebase auth** — optional now that D19 exists; it would add "recover my account on a
   new phone". `player_id` stays stable, so still cheap to add. `docs/firebase-setup.md`
   (from the scrapped repo) needs rewriting first.

## Local run

Tarik runs the server from **IntelliJ** (`SpacesurvivorsApplication` → Run). Do not start it
with `./mvnw spring-boot:run` — a terminal server holds port 8080 and breaks his Run.

```sh
curl localhost:8080/health    # {"status":"UP","db":"UP"} once it is up
./mvnw test                   # 88 green, needs local Postgres
```

DB: `spacesurvivors` on `localhost:5432`, role `ss_app` / `ss_app_local_dev` (dev-only
password, in the git-ignored `application-local.properties`; `JWT_SECRET` lives there too).
Inspect with DBeaver.
