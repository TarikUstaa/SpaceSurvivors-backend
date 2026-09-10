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
| secrets | container-app secrets | Key Vault is the better answer at scale and another moving part at this one. Revisit if a second service ever needs the same secret. |

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

Create an app registration and let it act on the resource group:

```bash
SUB=$(az account show --query id -o tsv)
RG=spacesurvivors-rg

APP_ID=$(az ad app create --display-name spacesurvivors-deploy --query appId -o tsv)
az ad sp create --id "$APP_ID"

az role assignment create \
  --assignee "$APP_ID" \
  --role Contributor \
  --scope "/subscriptions/$SUB/resourceGroups/$RG"
```

Then tell Azure to trust this repository's main branch — and only that:

```bash
az ad app federated-credential create --id "$APP_ID" --parameters '{
  "name": "github-main",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:TarikUstaa/SpaceSurvivors-backend:ref:refs/heads/main",
  "audiences": ["api://AzureADTokenExchange"]
}'
```

The `subject` line is the security boundary: a token minted for any other repository, or for
a branch other than `main`, will not be accepted.

Finally add five repository secrets under **Settings → Secrets and variables → Actions**:

| secret | value |
| --- | --- |
| `AZURE_CLIENT_ID` | the `$APP_ID` printed above |
| `AZURE_TENANT_ID` | `az account show --query tenantId -o tsv` |
| `AZURE_SUBSCRIPTION_ID` | `az account show --query id -o tsv` |
| `AZURE_RG` | `spacesurvivors-rg` |
| `AZURE_APP` | `spacesurvivors-api` |

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

## Known debt

- **The database user is the server admin.** `application.properties` defaults `DB_USER` to
  `ss_app`, but nothing has created that role on the Azure server yet, so the app connects
  with full rights it does not need. Least-privilege roles were already deferred once (D3).
- **The Postgres firewall allows any Azure service.** A container app's outbound address is
  not fixed, so it cannot be listed individually. The connection still needs the password and
  TLS. A private VNet is the correct fix and costs more.
- **Secrets are container-app secrets, not Key Vault.** Fine while one service holds them.
