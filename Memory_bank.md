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
- **Tests:** `./mvnw test` → **108 green** across 14 classes — unit, `@WebMvcTest` slices,
  repository tests and 5 integration tests. Every database test runs against a throwaway
  Postgres 18 container (D22), so the suite needs **Docker running** and nothing else — no
  local Postgres, no `application-local.properties`. About 19s end to end.
- **Rate limiting:** `POST /v1/auth/token` is capped at 30/minute per address by a filter
  ordered ahead of Spring Security, so a refused caller never reaches BCrypt (D21).
- **CI:** `.github/workflows/ci.yml` runs `./mvnw test` on every push and pull request —
  GitHub runners have Docker, so Testcontainers works with nothing added. ~1 min a run.
  First green run 2026-09-08, 110 tests.
- **Postman:** `docs/SpaceSurvivors.postman_collection.json` — 6 resource folders,
  30 requests, 56 assertions, self-verifying and re-runnable (`runId`-derived device ids).
  `newman run` green; it caught D20.
- **Runs from IntelliJ** (`SpacesurvivorsApplication` → Run), never `./mvnw spring-boot:run` —
  a terminal server holds port 8080 and breaks Tarik's Run.

### The immediate priority

Nothing is blocked. The docs are current as of 2026-09-09: `ogrenme-rehberi.md` §4 was
rewritten for the JWT chain, and §7/§8/§9 for rate limiting, Testcontainers and CI.

Next by value rather than urgency: the Unity client still has no in-game way to reach the
country field, `last_ip` still has no retention rule, and nothing is deployed.

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

### D22 — tests bring their own database (2026-09-08)

The suite used to talk to the Postgres on the developer's laptop, with credentials from
`application-local.properties` — a file that is git-ignored and therefore exists on exactly
one machine. A fresh clone could not run `./mvnw test` at all, which is the same as saying
there could be no CI. It was also shared mutable state: whatever the last manual poke at the
running server had done was still sitting in the database the tests then asserted against.

Each test context now starts its own Postgres container and throws it away afterwards.
`@ServiceConnection` is what makes this cheap — Spring Boot reads the connection details off
the container bean, so there is no second copy of the port and password to drift out of
step. **Docker is now a prerequisite for the test suite**, which is the price paid.

**A container that starts empty means Flyway runs from nothing on every run.** That is worth
more than the portability: the migrations are now exercised the way a new deployment meets
them, instead of being validated against a database somebody migrated by hand weeks ago.

**`TestDatabaseWiringTest` exists because its absence would be invisible.** This machine has
a Postgres on the usual port with the right schema in it. Had `@ServiceConnection` been
misconfigured and the datasource quietly fallen back to `localhost:5432`, every other test
would still have passed — against the wrong database, on one laptop, surfacing only as a
total failure on the first machine that lacked one. A green suite would have been evidence
of nothing. So the test asserts the JDBC URL is the container's ephemeral port and not 5432.
Third time this pattern has earned its place, after D20 and D21: **when a piece of wiring is
what makes something real, assert the wiring, because the behaviour it enables can be
produced by accident.**

**Two renames that cost time.** Testcontainers 2.x prefixed every module artifact with
`testcontainers-`, so `org.testcontainers:postgresql` from every tutorial does not resolve,
and `PostgreSQLContainer` moved from `org.testcontainers.containers` to
`org.testcontainers.postgresql` (and stopped being generic). Same shape of trap as the
Spring Boot 4 starter renames.

**Why `application-test.properties` and not `application.properties`.** A file named
`application.properties` under `src/test/resources` **shadows** the main one rather than
adding to it, so the application would silently lose its JPA, Flyway and Actuator settings.
A profile-specific file layers on top, and `@ActiveProfiles("test")` selects it in place of
`local` — which is the half of the fix that removes the dependency on the git-ignored file.

The rate limiter is off by default for tests there, rather than per-class. A cross-cutting
guard that is on during unrelated tests silently caps what those tests may do (D21).

**One unexplained flake.** During this work `AuthenticationIntegrationTest` failed once, one
test of nine, in a full-suite run. It has not reproduced in the five full runs since, and
the class passes alone. Recorded rather than dismissed: unreproducible is not the same as
fixed, and if it returns this is the first place to look.

### D21 — rate limiting the token endpoint, ahead of the security chain (2026-09-08)

Closes the open item D19 named. `POST /v1/auth/token` verifies a device secret with BCrypt,
which is deliberately slow — roughly 100 ms of CPU, on purpose, so a stolen table of hashes
is impractical to attack offline. That slowness is also a lever: the work is done whether or
not the secret checks out, so a few hundred requests a second from one socket starves every
real player, with no account and no valid credential needed.

**bucket4j for the algorithm, caffeine for the storage.** Two libraries for one feature
looks like a lot until you see they answer different questions. bucket4j counts tokens
atomically while many threads draw on one bucket — the part not worth hand-writing. Caffeine
bounds and expires the map of buckets, and **that bound is the same defence a second time**:
a limiter that remembers every address forever converts a CPU exhaustion into a memory
exhaustion. Eviction must never come sooner than a bucket refills, or forgetting a caller is
indistinguishable from raising their limit — hence `retention() = window × 2`.

**The ordering is the feature.** The filter registers at
`SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10`, ahead of Spring Security, so a refused
caller costs a map lookup and nothing else. Put it after the security chain and it still
answers 429 while no longer preventing the work it exists to prevent — it would look correct
and protect nothing. `RateLimitFilterTest` asserts the constant; `RateLimitIntegrationTest`
proves the filter is actually reached in the running chain, because a filter registered at
the wrong order or not at all passes every isolated test. Same gap as D20.

**Why the key is `getRemoteAddr()` and never `X-Forwarded-For`.** D14 said a client header
is input, not fact. Here it is sharper: a new value in that header would be a new bucket, so
trusting it does not merely record a false address, it removes the limit entirely. Behind a
proxy we run, `server.forward-headers-strategy=framework` rewrites `getRemoteAddr` itself and
leaves this code correct unchanged.

**30 per minute, and the number is a judgement not a default.** One address can then buy 3
seconds of BCrypt a minute, ~5% of a core, against a whole machine unlimited. The floor comes
from the other direction: carriers put thousands of subscribers behind one address (CGNAT),
and a per-address limit is therefore shared by strangers — at one token per hour per device,
30/min carries ~1800 devices behind one address. **Per-address limiting is blunt for exactly
that reason.** It stops one machine, not a botnet, and it is the ceiling of what is possible
before there is a real account to limit instead.

**Measured on the running server, 2026-09-08.** An accepted request costs **~82 ms** — that
is the BCrypt verify, and it confirms the estimate the whole design rests on. A refused one
costs **~1.1 ms**, and that figure is the entire HTTP round trip, so the filter's own work is
a fraction of it. Roughly **75x less CPU per refused request**, which is the number the
feature exists to produce. A burst of 100 simultaneous requests to one address let through
exactly 30 — the capacity, not 31 — so the bucket is precisely atomic under real Tomcat
threads, and the concurrent first contact still created exactly one player row (D13 holds
with the limiter in front of it).

**Refusals are logged at debug, not warn.** Under the attack this defends against they arrive
by the thousand, and D15 is the lesson about handled events flooding the error log. "How
many" belongs in metrics, not in logging.

**A trap found while building it.** `AuthenticationIntegrationTest` asks for a dozen tokens
and was sitting just under the ceiling — passing, but the next auth test anyone added would
have failed as a 429 unrelated to what they were testing. It now disables the limit
explicitly. **A cross-cutting guard silently changes the meaning of every test that crosses
it**; the tests for it belong in one place and out of everyone else's way.

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
- **Testcontainers 2.x renamed every module artifact** with a `testcontainers-` prefix,
  so `org.testcontainers:postgresql` does not resolve; it is
  `org.testcontainers:testcontainers-postgresql`. `PostgreSQLContainer` also moved from
  `org.testcontainers.containers` to `org.testcontainers.postgresql` and is no longer
  generic (`PostgreSQLContainer`, not `PostgreSQLContainer<?>`).
- **`src/test/resources/application.properties` shadows the main one**, it does not merge
  with it. Test-only settings belong in a profile-specific file such as
  `application-test.properties`, selected with `@ActiveProfiles`.
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
| F13 | `a31f569` | **Rate limiting** — D21. `POST /v1/auth/token` capped per address by a bucket4j token bucket in a bounded caffeine cache, in a filter ordered ahead of Spring Security so a refused caller never reaches BCrypt. 17 new tests, including one that proves the filter is actually reached in the running chain. |
| F14 | `d3e4763`, `c5ca96e`, `5129677` | **Testcontainers** — D22. Every database test now starts a disposable Postgres 18 rather than using the developer's own, so `./mvnw test` needs only Docker. `@DatabaseTest` composes the setup; `TestDatabaseWiringTest` proves the suite is really on the container and not falling back to localhost. |
| F15 | `920855f` | **CI** — `.github/workflows/ci.yml`, `./mvnw test` on every push and PR. Portable only because of D22. First green run: 110 tests on a GitHub runner. Adding a workflow needed the `workflow` scope on the push token. |
| F16 | `4b62e2e` | **Containerised** — multi-stage Dockerfile (JDK builds, JRE runs, non-root, heap sized from the container limit) plus `application-prod.properties`. Verified by running the image against a throwaway Postgres: 4s boot, Flyway applies V1+V2, full auth/progress/leaderboard round-trip, 401 on a wrong secret. |
| F17 | `43ca04b`, `050420f` | **Deploy pipeline** — `deploy/azure-setup.sh` (idempotent) and `.github/workflows/deploy.yml` (gated on CI passing on main, pushes to ghcr.io, rolls out, polls `/health`), plus `azure-oidc.sh` for the federated identity. |
| F18 | `123b3ca`, `1c6e13c`, `e0c92ee`, `610712f`, `3c6150c` | **Live on Azure** (2026-09-10). Five things broke on the way and every one was setup, not code: Free Trial refuses Postgres in West Europe (Italy North instead); `tr … \| head` under `pipefail` killed the password generator; `db create` takes `--name`, and answers `--database-name` with help and exit 0; the same trap in `firewall-rule create --rule-name`, which had never once worked; `az containerapp` cannot install on macOS 26 (pip's truststore reads an empty `platform.mac_ver()`), so the script drives ARM through `az rest`; `buildx` needed `setup-buildx-action` for the GHA cache; ghcr rejects the owner's capitalisation; and GitHub sent the *immutable* OIDC subject (`owner@id/repo@id`), which Azure compares verbatim. |
| F19 | `b8d6e4d`, `bc085b0`, `46079fa` | **Hardening.** Least-privilege DB roles (D3, finally paid): `ss_migrate` owns the schema and only Flyway uses it; `ss_app` may only DML and has no privilege on `flyway_schema_history`. Secrets moved to Key Vault, fetched by the app's managed identity — `deploy/.env.azure` now holds two names and no password. Also fixed a flaky forgery test (below). |

**Verification approach:** `./mvnw test` (88 cases, all layers) is the automated net;
`docs/SpaceSurvivors.postman_collection.json` run with `newman` is the end-to-end
regression suite (56 assertions). Every phase was also walked through in Postman by Tarik.
Twice an integration test found what a mocked one structurally could not (D20, and the
`updated_at` trigger).

---

## D24 — a test that forged nothing (2026-09-10)

`AuthenticationIntegrationTest.refusesATamperedToken` failed CI with
`Status expected:<401> but was:<200>` — reading exactly like authentication accepting a
forgery. It was not. The test never produced one.

An HS256 signature is 32 bytes, base64url-encoded in 43 characters: 258 bits of alphabet
carrying 256 bits of signature. The last character therefore contributes only **four**
meaningful bits, so the alphabet falls into groups of four — `A`-`D`, `E`-`H`, … — that decode
to an identical final byte. The test replaced the last character with `A` (or `B` if it was
already `A`). Whenever the signature ended in `A`, `B`, `C` or `D`, that changed the *text* and
not the *signature*: the "forged" token was the genuine one and 200 was correct.

Four characters in sixty-four — it failed about **6% of runs**, and always looked like a
security hole rather than an encoding artefact. Now it flips the *first* signature character
(a full six bits, always significant) and asserts the token actually differs.

**The general lesson, and the third variant of it this project has hit:** a test that
manipulates an encoded value has to manipulate the *decoded* one, or it is asserting about a
string rather than about the thing the string represents. Alongside D20/D21/D22's "assert the
wiring": here the wiring was fine and the *stimulus* was fake.

## D25 — the docs were the one thing the deployment published (2026-09-11)

`/swagger-ui.html` and `/v3/api-docs` were on `SecurityConfig`'s unauthenticated allow-list,
which is right on a laptop and wrong on the internet. The deployed service was handing anyone
who asked the complete map of the API: every path, every field, every validation rule, and a
form to fire requests from.

That is not a vulnerability by itself — every endpoint still demands a signed token, and none
of them leaked. It is a free head start, and nobody on the public internet has a use for it.

**Why not simply require a token for those paths.** Swagger UI is a page a *browser* loads,
and a browser cannot attach a bearer token to the request for the page itself. Putting docs
"behind authentication" means a session login — a cookie, a form — which this service
deliberately does not have: it is stateless and every request carries its own token. So the
honest options were "public" or "not served", and the deployment does not need them served.
The backoffice introduces exactly that kind of login; the docs can move behind it then.

`springdoc.api-docs.enabled=false` in the prod profile, and `SecurityConfig` now **reads that
same property** to decide whether the doc paths are public. Tying them together is the point:
neither can be changed into a lie by editing the other. `OpenApiDocsDisabledTest` asserts it
against a real context, deliberately paying for a second container — asserting it against the
context that has the docs *on* would be asserting nothing (see D20/D21/D22/D24).

Fixed the same day: the document had also gone stale. It still described
`Authorization: Device <device-id>` and said "the server believes whatever device id it is
sent" — true before D19 replaced it with signed tokens, a year out of date since.

## Backoffice — built and deployed 2026-09-11

Five screens at `/admin`, server-rendered with Thymeleaf in this same JAR: **players** (list),
**player detail** (account, save, scores, delete), **leaderboard** (per mode, remove a score),
**password**, **audit** (D28). Commits `5250a11` … `48a8a90`, then V4. 175 tests.

**The shape of it.** A second `SecurityFilterChain` at `@Order(1)` claiming `/admin/**`; the
API chain took `@Order(2)` and is otherwise untouched. The two halves disagree about every
security question and that is why they are separate: token vs password, stateless vs session,
CSRF off vs CSRF on, 401 vs a login page. `admin_user` (V3) is its own table — a player is a
*device* created on sight, an administrator is a *person* created deliberately, and one table
with a role column would put every player row one boolean away from administrator.

**No seeded admin.** A migration that inserts one publishes its hash, and a public BCrypt hash
is a password everybody has. `AdminBootstrap` creates it from `ADMIN_USERNAME`/`ADMIN_PASSWORD`
only while the table is empty, so the environment can never later overwrite a changed password.
In Azure the password is a generated Key Vault secret (`admin-password`).

### D26 — the endpoint nobody could exhaust was guarded; the one somebody could was not

`POST /admin/login` had no rate limit. It spends the same ~100 ms BCrypt hash per attempt as
`/v1/auth/token`, which has been limited since the start — identical denial-of-service lever.
The asymmetry that made it worse: a device secret is 256 bits of randomness and guessing it is
not an attack, while an administrator's password was chosen by a person and guessing it is.

Now limited at 10/min, in **its own bucket keyed by path as well as caller** — a shared bucket
would mean an attack on the login form locks players out of the game, and a busy CGNAT address
spends the administrator's attempts. Refused as a redirect, not a ProblemDetail: whoever hit it
is holding a browser.

**The general lesson:** the limiter was written for one endpoint and correctly explained itself
in terms of "the endpoint that costs a BCrypt hash". A second endpoint with exactly that cost
was added months later and nothing connected the two. Guard the *property*, not the path.

### D27 — CsrfFilter runs before ExceptionTranslationFilter

A POST to `/admin/login` without a CSRF token answered a bare **401**. `CsrfFilter` sits ahead
of `ExceptionTranslationFilter`, so its exception is never translated: it leaves the security
chain, the container dispatches to `/error`, and `/error` is not `/admin/**` — so the *API*
chain answers it. Fixed with an `accessDeniedHandler` on the admin chain (`CsrfConfigurer`
picks up whatever `exceptionHandling` registered), redirecting to `/admin/login?expired`. Only
CSRF failures; a signed-in non-admin keeps the 403.

**Worth remembering beyond this bug:** MockMvc does not perform the error dispatch, so in tests
the broken behaviour looked like a clean 403. Only driving the deployed service showed the 401.
A whole class of error-handling behaviour is invisible to MockMvc.

### D28 — the audit trail is a table, and it is append-only by having no delete (2026-09-11)

`admin_audit` (V4) + `/admin/audit`. Until now every administrative action announced itself with
`log.info`/`log.warn`, which on this deployment means a container that scales to zero, logs in
Log Analytics, and a query language nobody here writes. *Who deleted that player* had an answer
in principle and none in practice — while the backoffice had already gained the ability to
permanently delete somebody's save and scores. **An action that cannot be undone should at least
be one that cannot be denied.**

Five events, a closed set in `AdminAction`: `SIGNED_IN`, `SIGN_IN_FAILED`, `PASSWORD_CHANGED`,
`PLAYER_DELETED`, `SCORE_REMOVED`. Page views are not on the list; a log that fills with reads
is one nobody scrolls to the bottom of.

**Four decisions worth keeping:**

1. **`actor` and `target` are `text`, not foreign keys.** The sharpest case decides it: the most
   important row this table holds is *a player was deleted*. A FK with `ON DELETE CASCADE` would
   erase that row along with them — the log deleting its own evidence — and one without a cascade
   would refuse the delete outright. The display name is copied into `summary` at write time,
   because seconds later there is nowhere left to read it from. A record must outlive what it
   describes.
2. **Append-only by absence.** `AdminAuditRepository extends Repository` (the bare marker) and
   declares exactly `save`, `count` and one finder, so no delete exists to be called anywhere in
   the application. The entity has no setters either. A test asserts the method list by
   reflection — the guarantee is one convenient signature away from being lost and nothing else
   would notice. The stronger version, `REVOKE UPDATE, DELETE ON admin_audit FROM ss_app`, is
   noted in V4 and deferred: `db-roles.sql` runs before the schema exists on a first setup.
3. **`@Enumerated(EnumType.STRING)`, never `ORDINAL`** — ordinal stores the constant's position,
   so reordering the enum silently reinterprets every row already written. History changing
   because somebody tidied a Java file is not a tradeoff, it is a defect.
4. **Two different failure policies, on purpose.** The destructive actions audit *inside their
   own transaction* (`AdminAccountController.change` gained `@Transactional` for exactly this),
   so if the audit row cannot be written the delete rolls back with it — no destructive action
   without a record. The sign-in handlers swallow and log instead: they run outside any
   transaction, the event has already happened, and letting a broken log table lock the
   administrator out escalates a logging fault into a lockout over the least important rows.

**Half the tests are about what must *not* be recorded** — a delete refused for a mistyped name,
a removal that touched zero rows, a rejected password change. A log that records attempts as
though they were events is worse than no log, because it is read as one and it lies.

**What the tests found:** the first run failed with four rows already in the table. Other admin
test classes are not `@Transactional`, sign in for real, and their entries commit and stay — the
table behaving exactly as an append-only table should. The fix was a watermark (`max(audit_id)`
in `@BeforeEach`, then `where audit_id > :since`), which is the `bigserial` key from V4 earning
its keep: with a uuid key "everything since this point" could not have been expressed without
emptying somebody else's rows.

## Open / next

*Priority order.*

> **Both Unity clients landed 2026-09-09** (game repo `8ec06a3` … `e2f2272`): the main menu
> shows the ranked board with Infinite/Campaign tabs, and the Profile screen renames through
> `PATCH /v1/player`. Every endpoint this service exposes now has a caller.

1. **`country` has no source.** The column stays NULL until a host or CDN supplies a country
   header, and the Profile screen shows a dash for it. Locally `last_ip` is always `::1`.
2. **IP retention.** `last_ip` is personal data, stored deliberately; it needs a purpose and
   a "delete IPs older than N days" job before this is public.
3. **Pagination** — the board is capped at 100 rows and there is no `page`. Fine now,
   wrong the day there are more players than that.
4. ~~**Least-privilege DB roles**~~ — **done 2026-09-10** (`b8d6e4d`). See F19.
5. ~~**Azure deploy**~~ — **live 2026-09-10.**
   `https://spacesurvivors-api.salmonmeadow-a79134b3.italynorth.azurecontainerapps.io`
   Container App (scales to zero) + Postgres B1ms + Key Vault, Italy North, deployed by CI on
   every green push to main. Unity's `BackendConfig.DefaultBaseUrl` points at it.
   **The one open piece:** Postgres still admits any Azure service, because a Container App's
   outbound IP is not promised to be stable and the real fix (private networking) means
   recreating the server *and* the environment — which changes the public API hostname the
   game ships with. Tarik's call, deferred 2026-09-10. The connection needs password + TLS
   regardless.
   **Free Trial expires ~30 days in**; without an upgrade to Pay-As-You-Go the subscription is
   disabled and the service stops. Spending limit is on, so nothing is ever charged silently.

6. **Firebase auth** — optional now that D19 exists; it would add "recover my account on a
   new phone", which is the honest gap in device-based identity. `player_id` stays stable,
   so still cheap to add. `docs/firebase-setup.md` (from the scrapped repo) needs rewriting.
7. ~~**Backoffice**~~ — **live 2026-09-11**, four screens. See the section above. Still open
   within it: search and pagination once the player list outgrows a screen, ending other
   sessions when a password changes (`SessionRegistry`), a second administrator account, and
   a real audit table instead of log lines.

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
