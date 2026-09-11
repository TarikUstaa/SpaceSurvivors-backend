#!/usr/bin/env bash
#
# Creates everything the service needs to run in Azure, once. Run it after `az login`.
#
# It is idempotent: every step checks whether the thing already exists, so re-running after
# a failure picks up where it stopped instead of erroring or building a second copy. That
# matters more than it sounds — the first run of something like this rarely finishes.
#
# What it builds, and why these choices:
#
#   Container Apps, not App Service. It scales to zero: with no players connected the
#   compute bill is nothing, and a cold start is a few seconds. For a game whose traffic is
#   "Tarik and some friends" that is the difference between pennies and a monthly charge.
#
#   ghcr.io, not Azure Container Registry. ACR's cheapest tier is a flat ~$5/month for
#   somewhere to keep an image; GitHub's registry comes with the repository. The image is
#   published public, which is what keeps this script free of registry credentials: the
#   workflow pushes with the token GitHub already gives it, and Azure pulls anonymously.
#   Nothing sensitive is in the image — .dockerignore excludes the local properties file and
#   every secret arrives from the environment at runtime.
#
#   Postgres Flexible Server, Burstable B1ms — the smallest that exists. Covered by the
#   12-month free allowance on a new subscription; roughly $13-15/month after that.
#
#   Key Vault holds the four passwords. The container app stores only a reference to each and
#   fetches the value at start with its own managed identity, so no secret is written into the
#   app's configuration, into this repository, or into a file on the machine that ran this.
#   `az containerapp show` returns the name and the vault URL; the value is not there to leak.
#
# It talks to the Container Apps ARM API through `az rest` rather than `az containerapp`.
# That is not stylistic: the containerapp extension cannot install on macOS 26, because pip's
# vendored truststore reads platform.mac_ver(), gets an empty string, and dies on int("").
# `az rest` is core CLI and has no such dependency, so this script runs anywhere `az` does.
#
# deploy/.env.azure records only which server and vault were created — both names, neither a
# secret. It stays git-ignored anyway, because the pair identifies the deployment.

set -euo pipefail

# ── settings ──────────────────────────────────────────────────────────────
# Italy North, not West Europe: on a Free Trial subscription Postgres Flexible Server answers
# "Subscriptions are restricted from provisioning in this region" for West Europe and several
# of its neighbours. Italy North is unrestricted and is the closest unrestricted region to
# Turkey. `az postgres flexible-server list-skus --location <region>` reports this — the
# restriction shows up as OfferRestricted=Enabled with that reason, not as a missing SKU.
LOCATION="${LOCATION:-italynorth}"
RG="${RG:-spacesurvivors-rg}"
PG_ADMIN="${PG_ADMIN:-ssadmin}"
PG_DB="spacesurvivors"
ENVIRONMENT="${ENVIRONMENT:-spacesurvivors-env}"
WORKSPACE="${WORKSPACE:-ss-logs}"
APP="${APP:-spacesurvivors-api}"
IMAGE="${IMAGE:-ghcr.io/tarikustaa/spacesurvivors-backend:latest}"
# Who the backoffice's first administrator is. Not a secret — the password is, and that one
# is generated below and kept in the vault.
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
API_VERSION="2024-03-01"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="$HERE/.env.azure"

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

# ── preflight ─────────────────────────────────────────────────────────────
command -v az >/dev/null || { echo "az CLI not found — install it first."; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found."; exit 1; }
az account show >/dev/null 2>&1 || { echo "Not logged in. Run: az login"; exit 1; }

say "Subscription"
az account show --query '{name:name, id:id}' -o tsv
SUB="$(az account show --query id -o tsv)"
ARM="https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers"

# Names of things created on a previous run. Not secrets — but they identify the deployment,
# so the file stays out of git.
# shellcheck disable=SC1090
[[ -f "$STATE_FILE" ]] && source "$STATE_FILE"
PG_SERVER="${PG_SERVER:-spacesurvivors-pg-$RANDOM}"   # must be globally unique
VAULT="${VAULT:-ss-kv-$RANDOM}"                       # ditto

# These are not registered on a brand-new subscription, and the failure when they are not is
# an opaque one — so ask for them up front.
say "Registering resource providers (safe to repeat, may take a minute)"
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.DBforPostgreSQL --wait
az provider register --namespace Microsoft.OperationalInsights --wait
az provider register --namespace Microsoft.KeyVault --wait
az provider register --namespace Microsoft.OperationsManagement --wait 2>/dev/null || true

say "Resource group: $RG"
az group create --name "$RG" --location "$LOCATION" -o none

# ── key vault ─────────────────────────────────────────────────────────────
if az keyvault show -g "$RG" -n "$VAULT" >/dev/null 2>&1; then
    say "Key Vault $VAULT already exists"
else
    say "Creating Key Vault $VAULT"
    # RBAC rather than the older access policies: the same role model as everything else, and
    # the app's identity can be given read-only access to secrets and nothing more.
    az keyvault create -g "$RG" -n "$VAULT" -l "$LOCATION" \
        --enable-rbac-authorization true --retention-days 7 -o none
fi
VAULT_URI="https://${VAULT}.vault.azure.net"

# Creating a vault does not grant the creator access to what is in it.
say "Granting the signed-in user permission to write secrets"
CALLER="$(az ad signed-in-user show --query id -o tsv)"
az role assignment create --assignee-object-id "$CALLER" --assignee-principal-type User \
    --role "Key Vault Secrets Officer" \
    --scope "$ARM/Microsoft.KeyVault/vaults/$VAULT" -o none 2>/dev/null || true
# RBAC takes a little while to be visible to the data plane.
sleep 20

# ── secrets ───────────────────────────────────────────────────────────────
# Generated once and kept in the vault. Regenerating the JWT key would invalidate every token
# already in a player's hands, and regenerating a database password would leave the running
# revision holding a credential that no longer works — so existing values are never touched.
#
# Azure rejects an admin password that does not use at least three of {uppercase, lowercase,
# digit, symbol}, and base64 output, while it almost always contains all three, is not
# guaranteed to. The "Ss1" prefix makes it certain.
#
# openssl rather than `tr -dc … < /dev/urandom | head -c N`: in that pipeline head exits as
# soon as it has N bytes, tr takes SIGPIPE, and under `set -o pipefail` the whole command
# reports failure — so `set -e` would kill the script before it wrote anything.
ensure_secret() {
    local name="$1" value="$2"
    if az keyvault secret show --vault-name "$VAULT" -n "$name" >/dev/null 2>&1; then
        echo "  $name — already in the vault"
    else
        az keyvault secret set --vault-name "$VAULT" -n "$name" --value "$value" -o none
        echo "  $name — stored"
    fi
}

say "Secrets in $VAULT"
ensure_secret pg-admin-password    "Ss1$(openssl rand -base64 24 | tr -d '/+=')"
ensure_secret app-db-password      "Ss1$(openssl rand -base64 24 | tr -d '/+=')"
ensure_secret migrate-db-password  "Ss1$(openssl rand -base64 24 | tr -d '/+=')"
ensure_secret jwt-secret           "$(openssl rand -hex 32)"   # 32 bytes — HS256 wants that much

# The backoffice's first administrator. Generated rather than chosen, for the same reason the
# database passwords are: a password typed into a setup script is a password that ends up in a
# shell history, and this one opens a page with a delete button on it.
#
# It matters exactly once. AdminBootstrap creates the account on the first start against an
# empty admin_user table and ignores these settings from then on, so this is the value to read
# out of the vault for the first sign-in — and the vault copy becomes a record of what the
# first password was, not what the current one is.
ensure_secret admin-password       "$(openssl rand -base64 18 | tr -d '/+=')"

PG_PASSWORD="$(az keyvault secret show --vault-name "$VAULT" -n pg-admin-password --query value -o tsv)"

umask 077
cat > "$STATE_FILE" <<EOF
# Written by azure-setup.sh. Names, not secrets — the passwords live in the Key Vault named
# here. Git-ignored regardless, because the pair identifies the deployment.
PG_SERVER='$PG_SERVER'
VAULT='$VAULT'
EOF

# ── postgres ──────────────────────────────────────────────────────────────
if az postgres flexible-server show -g "$RG" -n "$PG_SERVER" >/dev/null 2>&1; then
    say "Postgres server $PG_SERVER already exists"
else
    say "Creating Postgres $PG_SERVER (several minutes)"
    az postgres flexible-server create \
        --resource-group "$RG" \
        --name "$PG_SERVER" \
        --location "$LOCATION" \
        --admin-user "$PG_ADMIN" \
        --admin-password "$PG_PASSWORD" \
        --tier Burstable \
        --sku-name Standard_B1ms \
        --storage-size 32 \
        --storage-auto-grow Enabled \
        --version 16 \
        --public-access 0.0.0.0 \
        --yes -o none
fi

# 0.0.0.0-0.0.0.0 is Azure's special rule meaning "other Azure services", not "the whole
# internet" — the container app's outbound address is not fixed, so it cannot be listed.
# Everything still needs the password and TLS. Narrowing this to a private VNet is the proper
# answer and costs more; noted in deploy/README.md rather than done.
#
# -s names the server and -n names the rule; there is no --rule-name, and passing one makes
# the CLI print its help and exit 0 — so this step silently did nothing for a while and was
# only noticed because --public-access above had already added an equivalent rule. Hence the
# check afterwards.
say "Firewall rule for Azure-internal callers"
az postgres flexible-server firewall-rule create \
    -g "$RG" -s "$PG_SERVER" -n allow-azure-services \
    --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0 -o none 2>/dev/null || true

az postgres flexible-server firewall-rule list -g "$RG" -s "$PG_SERVER" \
    --query "[?startIpAddress=='0.0.0.0'] | length(@)" -o tsv | grep -qv '^0$' \
    || { echo "No rule admitting Azure services — the app will not reach the database."; exit 1; }

# --name, not --database-name: the CLI rejects the longer spelling by printing its help text
# and exiting 0, so a `|| true` here would hide the fact that nothing was created — which is
# why the next line checks instead of trusting.
say "Database $PG_DB"
az postgres flexible-server db create \
    --resource-group "$RG" --server-name "$PG_SERVER" --name "$PG_DB" -o none 2>/dev/null || true

az postgres flexible-server db list -g "$RG" -s "$PG_SERVER" --query "[].name" -o tsv \
    | grep -qx "$PG_DB" || { echo "Database $PG_DB was not created."; exit 1; }

PG_HOST="$(az postgres flexible-server show -g "$RG" -n "$PG_SERVER" --query fullyQualifiedDomainName -o tsv)"
# sslmode=require: Azure refuses an unencrypted connection anyway, and being explicit means
# the driver fails with a clear message rather than a handshake error if that ever changes.
DB_URL="jdbc:postgresql://${PG_HOST}:5432/${PG_DB}?sslmode=require"

# ── logs ──────────────────────────────────────────────────────────────────
# Without this a Container App keeps no logs at all. There is a live event stream and nothing
# retained, so an exception at three in the morning leaves no trace to read at nine — which is
# precisely when logs are the only thing there is. 30 days is plenty, and this much ingestion
# costs pennies.
if az monitor log-analytics workspace show -g "$RG" -n "$WORKSPACE" >/dev/null 2>&1; then
    say "Log Analytics workspace $WORKSPACE already exists"
else
    say "Creating Log Analytics workspace $WORKSPACE"
    az monitor log-analytics workspace create -g "$RG" -n "$WORKSPACE" -l "$LOCATION" \
        --retention-time 30 -o none
fi
WS_ID="$(az monitor log-analytics workspace show -g "$RG" -n "$WORKSPACE" --query customerId -o tsv)"
WS_KEY="$(az monitor log-analytics workspace get-shared-keys -g "$RG" -n "$WORKSPACE" --query primarySharedKey -o tsv)"

# ── container apps environment ────────────────────────────────────────────
ENV_URL="$ARM/Microsoft.App/managedEnvironments/$ENVIRONMENT?api-version=$API_VERSION"

ENV_BODY="$(mktemp)"
WS_ID="$WS_ID" WS_KEY="$WS_KEY" LOCATION="$LOCATION" python3 - "$ENV_BODY" <<'__ENVJSON__'
import json, os, sys
json.dump({"location": os.environ["LOCATION"], "properties": {"appLogsConfiguration": {
    "destination": "log-analytics",
    "logAnalyticsConfiguration": {"customerId": os.environ["WS_ID"],
                                  "sharedKey": os.environ["WS_KEY"]}}}},
          open(sys.argv[1], "w"))
__ENVJSON__

if [[ "$(az rest --method get --url "$ENV_URL" --query properties.provisioningState -o tsv 2>/dev/null)" == "Succeeded" ]]; then
    say "Container Apps environment $ENVIRONMENT already exists — refreshing its log settings"
    az rest --method put --url "$ENV_URL" --body "@$ENV_BODY" -o none
    rm -f "$ENV_BODY"
else
    say "Creating Container Apps environment $ENVIRONMENT"
    az rest --method put --url "$ENV_URL" --body "@$ENV_BODY" -o none
    rm -f "$ENV_BODY"
    for _ in $(seq 1 40); do
        state="$(az rest --method get --url "$ENV_URL" --query properties.provisioningState -o tsv 2>/dev/null || true)"
        [[ "$state" == "Succeeded" ]] && break
        [[ "$state" == "Failed" ]] && { echo "environment failed to provision"; exit 1; }
        sleep 15
    done
fi

# ── container app ─────────────────────────────────────────────────────────
# The image must already exist in ghcr.io and be public — Container Apps validates the pull
# while creating, and a create against a missing image leaves the app Failed with no revision
# at all. Push it first by letting the Deploy workflow run (see deploy/README.md).
#
# DB_USER is ss_app and Flyway runs as ss_migrate; deploy/db-roles.sh creates both. On a first
# setup those roles do not exist yet, so run this, then db-roles.sh, then this again — the
# second pass is what starts a revision that can actually connect.
APP_URL="$ARM/Microsoft.App/containerApps/$APP?api-version=$API_VERSION"

write_app_body() {
    DB_URL="$DB_URL" IMAGE="$IMAGE" LOCATION="$LOCATION" VAULT_URI="$VAULT_URI" \
    ADMIN_USERNAME="$ADMIN_USERNAME" \
    ENV_ID="/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.App/managedEnvironments/$ENVIRONMENT" \
    python3 - "$1" <<'PY'
import json, os, sys
v = os.environ["VAULT_URI"]
def vault(name):
    # identity "system" = the app's own managed identity fetches this at start. The value is
    # never stored in the app, so `show` returns this reference and nothing to leak.
    return {"name": name, "keyVaultUrl": f"{v}/secrets/{name}", "identity": "system"}

json.dump({
    "location": os.environ["LOCATION"],
    # System-assigned: an identity whose lifetime is the app's, so deleting the app deletes it
    # and there is no orphaned principal left holding access to the vault.
    "identity": {"type": "SystemAssigned"},
    "properties": {
        "managedEnvironmentId": os.environ["ENV_ID"],
        "configuration": {
            # allowInsecure false: the ingress serves HTTPS and refuses plain HTTP, so a client
            # cannot be talked into sending its device secret in the clear.
            "ingress": {"external": True, "targetPort": 8080,
                        "transport": "auto", "allowInsecure": False},
            "secrets": [vault("jwt-secret"), vault("app-db-password"),
                        vault("migrate-db-password"), vault("admin-password")],
        },
        "template": {
            "containers": [{
                "name": "api",
                "image": os.environ["IMAGE"],
                "resources": {"cpu": 0.5, "memory": "1Gi"},
                "env": [
                    {"name": "DB_URL",          "value": os.environ["DB_URL"]},
                    {"name": "DB_USER",         "value": "ss_app"},
                    {"name": "DB_PASSWORD",     "secretRef": "app-db-password"},
                    {"name": "JWT_SECRET",      "secretRef": "jwt-secret"},
                    {"name": "FLYWAY_USER",     "value": "ss_migrate"},
                    {"name": "FLYWAY_PASSWORD", "secretRef": "migrate-db-password"},
                    # Read only while admin_user is empty — see admin/AdminBootstrap. Left in
                    # place afterwards so that losing the account (a dropped table, a fresh
                    # database) has a way back in that does not involve hand-written SQL.
                    {"name": "ADMIN_USERNAME",  "value": os.environ["ADMIN_USERNAME"]},
                    {"name": "ADMIN_PASSWORD",  "secretRef": "admin-password"},
                ],
            }],
            # min 0 is the whole reason for choosing Container Apps: idle costs nothing. The
            # price is a few seconds of cold start on the first request after a quiet spell.
            "scale": {"minReplicas": 0, "maxReplicas": 2},
        },
    },
}, open(sys.argv[1], "w"))
PY
}

say "Container app $APP"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT
write_app_body "$BODY"
az rest --method put --url "$APP_URL" --body "@$BODY" -o none

# The identity does not exist until the app does, and the app cannot read the vault until the
# identity has been granted access — so the grant comes second, and the app is written again
# afterwards to start a revision that can actually fetch its secrets.
PRINCIPAL="$(az rest --method get --url "$APP_URL" --query identity.principalId -o tsv)"
say "Letting the app read the vault"
az role assignment create --assignee-object-id "$PRINCIPAL" --assignee-principal-type ServicePrincipal \
    --role "Key Vault Secrets User" \
    --scope "$ARM/Microsoft.KeyVault/vaults/$VAULT" -o none 2>/dev/null || true
sleep 20
az rest --method put --url "$APP_URL" --body "@$BODY" -o none

for _ in $(seq 1 40); do
    state="$(az rest --method get --url "$APP_URL" --query properties.provisioningState -o tsv 2>/dev/null || true)"
    case "$state" in
        Succeeded) break ;;
        Failed|Canceled)
            echo "Container app failed to provision. The usual causes are that"
            echo "$IMAGE cannot be pulled (does it exist, is the package public?),"
            echo "or that db-roles.sh has not run yet so ss_app does not exist."
            exit 1 ;;
    esac
    sleep 15
done

FQDN="$(az rest --method get --url "$APP_URL" --query properties.configuration.ingress.fqdn -o tsv)"

say "Done"
cat <<EOF

  API           https://${FQDN}
  Health        https://${FQDN}/health
  Postgres      ${PG_HOST}
  Key Vault     ${VAULT_URI}
  Logs          workspace ${WORKSPACE}, kept 30 days
  State         ${STATE_FILE}  (names only — no secrets)

Next:
  1. ./deploy/db-roles.sh, if ss_app and ss_migrate do not exist yet, then run this again.
  2. curl https://${FQDN}/health          → {"status":"UP","db":"UP"}
  3. Point Unity's BackendConfig.DefaultBaseUrl at https://${FQDN}
  4. ./deploy/azure-oidc.sh and the repository secrets, so CI deploys on every green push.

If /health says db is DOWN, the first request also had to start the container from zero and
let Flyway migrate an empty database — give it a few seconds and ask again.
EOF
