# SpaceSurvivors — backend

Cloud save and leaderboards for the [SpaceSurvivors](https://github.com/TarikUstaa/SpaceSurvivors)
Unity game. The game runs fully offline off a local cache; this service is additive, so
nothing in the game breaks while it is unreachable.

Java 25 · Spring Boot 4.1.1 · PostgreSQL 18 · Maven

## Running the tests

```sh
./mvnw test
```

**Docker must be running.** Every database test starts a throwaway Postgres container and
throws it away afterwards, so the suite needs no database installed and no configuration —
that is the whole point, and it is why the tests pass on a machine nobody has set up by hand.

## Running the server

Needs a local Postgres and a `src/main/resources/application-local.properties`, which is
git-ignored because it holds this machine's credentials. Copy the template and fill it in:

```sh
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
```

Then start `SpacesurvivorsApplication` from the IDE, or:

```sh
./mvnw spring-boot:run
```

```sh
curl localhost:8080/health
```

The database is `spacesurvivors` on `localhost:5432`. Flyway builds the schema on first
start, so an empty database is enough — never edit a migration that has already been
applied.

## Finding your way around

| | |
|---|---|
| `Memory_bank.md` | Every decision and why it was made. **Start here.** |
| `docs/ogrenme-rehberi.md` | How the code works, walked through class by class (Turkish) |
| `docs/savunma-notlari.md` | The questions a reviewer asks, and the reasoning behind each answer (Turkish) |
| `docs/SpaceSurvivors.postman_collection.json` | The API as a runnable, self-verifying collection |
| `/swagger-ui.html` | The API description, generated from the controllers |

## API

Every endpoint needs `Authorization: Bearer <token>` except `POST /v1/auth/token`,
`GET /health` and the documentation.

| | |
|---|---|
| `POST /v1/auth/token` | device secret → a one-hour token. Rate limited. |
| `GET` / `PATCH /v1/player` | who you are; change your display name |
| `GET` / `PUT /v1/progress` | the save blob, with optimistic locking |
| `GET` / `POST /v1/leaderboard` | the board, and your best run per mode |
