# Deploying

Two things live here: a script that builds the Azure resources once, and the explanation of
the five GitHub secrets that let CI deploy to them afterwards. The pipeline this produces is:

```
push to main → CI runs 110 tests → Deploy builds the image, pushes it to ghcr.io,
               tells the container app to use it, then polls /health until it answers
```

## Shape of it

| piece | choice | why |
| --- | --- | --- |
| compute | Container Apps | Scales to zero. Idle costs nothing, which suits traffic that is "a few friends". |
| registry | ghcr.io, **public package** | Comes with the repository; Azure's own registry is a flat ~$5/month for storage alone. Public is what keeps credentials out of this entirely — Actions pushes with the token it already has, Azure pulls anonymously. The image holds no secrets: `.dockerignore` excludes the local properties file and everything sensitive arrives from the environment at runtime. |
| database | Postgres Flexible Server, B1ms | The smallest tier. Free for 12 months on a new subscription, ~$13-15/month after. |
| region | Italy North | **Not West Europe.** A Free Trial subscription is refused there: *"Subscriptions are restricted from provisioning in this region."* Italy North is the closest unrestricted region to Turkey. `az postgres flexible-server list-skus --location <r>` shows this as `OfferRestricted: Enabled`, not as a missing SKU — so a naive SKU check reports "available" and the create still fails. |
| secrets | Key Vault, read by managed identity | The container app stores only a reference to each secret and fetches the value at start as itself. `az containerapp show` returns the name and the vault URL; the value is not there to leak, and no password is written to the machine that ran the setup. |

## 1. Create the resources

Install the CLI and sign in:

```bash
brew install azure-cli
az login
```

Then:

```bash
./deploy/azure-setup.sh
```

It generates the database password and the JWT signing key itself, writes them to
`deploy/.env.azure` (git-ignored) and hands them to Azure as container-app secrets. Re-running
it is safe — every step checks for what it is about to create, and it reuses the secrets from
that file rather than inventing new ones. Regenerating the JWT key would invalidate every
token already in a player's hands.

It asks for nothing. The container app step needs the image to already exist in ghcr.io and
be public, so on a first-ever setup let the Deploy workflow run once (below) before this
reaches that step — Container Apps validates the pull while creating, and a create against a
missing image leaves the app `Failed` with no revision at all.

**After the very first workflow run, make the package public**: GitHub publishes a new
package as private regardless of anything else. Go to the repository → *Packages* →
`spacesurvivors-backend` → *Package settings* → *Change visibility* → Public. Skip this and
Azure cannot pull, with a message that says only `UNAUTHORIZED`.

When it finishes it prints the API's URL. Check it:

```bash
curl https://<the-printed-host>/health     # {"status":"UP","db":"UP"}
```

The first request after a deploy has to start the container from zero *and* let Flyway apply
the migrations against an empty database, so give it a few seconds before concluding
something is broken.

## 2. Let CI deploy

The Deploy workflow signs in to Azure with OIDC: GitHub proves the workflow's identity and
Azure returns a short-lived token. Nothing long-lived is stored in the repository, so there
is no client secret to leak or rotate — which matters, because that secret would be a key to
the whole subscription.

```bash
./deploy/azure-oidc.sh
```

It registers an identity, scopes it to this one resource group (not the subscription), adds a
federated credential pinned to this repository's main branch, and prints the five values to
paste in. That `subject` is the security boundary: Azure compares it verbatim against the
claim in the token GitHub minted, so a token for another repository or another branch does not
match — a fork or a pull request cannot deploy no matter what its workflow file says.

**If the deploy fails with `AADSTS700213: No matching federated identity record found`**, read
the subject it quotes. GitHub has two spellings of that claim and which one an account sends is
not something the workflow chooses:

```
repo:OWNER/REPO:ref:refs/heads/main                             ← plain
repo:OWNER@<owner id>/REPO@<repo id>:ref:refs/heads/main        ← immutable, survives a rename
```

Verbatim comparison means a credential for one does not satisfy the other. Both ids are in the
error message; re-run with them and the script adds the second credential alongside the first:

```bash
GH_OWNER_ID=<owner id> GH_REPO_ID=<repo id> ./deploy/azure-oidc.sh
```

Add what it prints under **Settings → Secrets and variables → Actions**:
`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_RG`, `AZURE_APP`.
None of the five is a password — they are identifiers. The federated credential is what makes
the arrangement safe, which is exactly why there is no client secret to guard.

## 3. Point the game at it

In the Unity project, set `BackendConfig.DefaultBaseUrl` to the printed `https://…` host and
ship a build. Until then the game talks to whatever is running on localhost.

## If `az containerapp` will not install

On macOS 26 the extension fails with `Pip failed with status code 2`. The cause is upstream
and nothing to do with this project: pip's vendored `truststore` reads `platform.mac_ver()`,
gets an empty string from the CLI's bundled Python 3.14, and dies on `int("")`. Installing the
wheel by hand does not help either — it needs `kubernetes`, which drags in its own tree.

`azure-setup.sh` therefore drives the Container Apps ARM API through `az rest`, which is core
CLI and needs no extension. The Deploy workflow still uses `az containerapp update`, because
the extension installs without trouble on a GitHub runner.

## Rolling back

Every deploy tags the image with its commit sha as well as `latest`, so a rollback is naming
an older one:

```bash
az containerapp update -g spacesurvivors-rg -n spacesurvivors-api \
  --image ghcr.io/tarikustaa/spacesurvivors-backend:<the good sha>
```

## Database roles

The service does not connect as the server administrator. `deploy/db-roles.sh` applies
`db-roles.sql`, which splits that one login into the two jobs that exist:

| role | may | used by |
| --- | --- | --- |
| `ss_migrate` | own and change the schema | Flyway, at startup |
| `ss_app` | `SELECT/INSERT/UPDATE/DELETE` rows, nothing else | every request |

`ss_app` cannot create, alter or drop anything, and has no privilege at all on
`flyway_schema_history` — an account that can rewrite the ledger is an account that can
convince Flyway a migration already ran. The script proves this rather than asserting it: it
reconnects as `ss_app` and prints what it can and cannot do.

Switching an existing deployment over has an order, and getting it wrong crash-loops the app,
because an image that does not know about `spring.flyway.user` would try to migrate as
`ss_app`:

1. Set `FLYWAY_USER` / `FLYWAY_PASSWORD` while `DB_USER` is still the admin. The running
   image ignores them.
2. Deploy the image that reads them — it migrates as `ss_migrate`, still queries as admin.
3. Switch `DB_USER` / `DB_PASSWORD` to `ss_app`.

## Known debt
- **The Postgres firewall allows any Azure service.** A container app's outbound address is
  not fixed, so it cannot be listed individually. The connection still needs the password and
  TLS. A private VNet is the correct fix and costs more.
- **Secrets are container-app secrets, not Key Vault.** Fine while one service holds them.
