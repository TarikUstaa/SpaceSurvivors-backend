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
#   published public, which is what keeps this script free of credentials entirely: the
#   workflow pushes with the token GitHub already gives it, and Azure pulls anonymously.
#   Nothing sensitive is in the image — .dockerignore keeps the local properties file out and
#   every secret arrives from the environment at runtime.
#
#   Postgres Flexible Server, Burstable B1ms — the smallest that exists. Covered by the
#   12-month free allowance on a new subscription; roughly $13-15/month after that.
#
# It talks to the Container Apps ARM API through `az rest` rather than `az containerapp`.
# That is not stylistic: the containerapp extension cannot install on macOS 26, because pip's
# vendored truststore reads platform.mac_ver(), gets an empty string, and dies on int("").
# `az rest` is core CLI and has no such dependency, so this script runs anywhere `az` does.
#
# Nothing here prints a secret. The two the app needs (the database password and the JWT
# signing key) are generated locally, handed to Azure as container-app secrets, and written
# to deploy/.env.azure — which is git-ignored — so a later run reuses them rather than
# invalidating every token already issued.

set -euo pipefail

# ── settings ──────────────────────────────────────────────────────────────
# Italy North, not West Europe: on a Free Trial subscription Postgres Flexible Server answers
# "Subscriptions are restricted from provisioning in this region" for West Europe and several
# of its neighbours. Italy North is unrestricted and is the closest unrestricted region to
# Turkey. `az postgres flexible-server list-skus --location <region>` reports this — the
# restriction shows up as OfferRestricted=Enabled with that reason, not as a missing SKU.
LOCATION="${LOCATION:-italynorth}"
RG="${RG:-spacesurvivors-rg}"
PG_SERVER="${PG_SERVER:-spacesurvivors-pg-$RANDOM}"   # must be globally unique
PG_ADMIN="${PG_ADMIN:-ssadmin}"
PG_DB="spacesurvivors"
ENVIRONMENT="${ENVIRONMENT:-spacesurvivors-env}"
APP="${APP:-spacesurvivors-api}"
IMAGE="${IMAGE:-ghcr.io/tarikustaa/spacesurvivors-backend:latest}"
API_VERSION="2024-03-01"

SECRETS_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.env.azure"

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

# ── preflight ─────────────────────────────────────────────────────────────
command -v az >/dev/null || { echo "az CLI not found — install it first."; exit 1; }
az account show >/dev/null 2>&1 || { echo "Not logged in. Run: az login"; exit 1; }

say "Subscription"
az account show --query '{name:name, id:id}' -o tsv
SUB="$(az account show --query id -o tsv)"
ARM="https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers"

# These are not registered on a brand-new subscription, and the failure when they are not is
# an opaque one — so ask for them up front.
say "Registering resource providers (safe to repeat, may take a minute)"
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.DBforPostgreSQL --wait
az provider register --namespace Microsoft.OperationalInsights --wait

# ── secrets ───────────────────────────────────────────────────────────────
if [[ -f "$SECRETS_FILE" ]]; then
    say "Reusing secrets from $SECRETS_FILE"
    # shellcheck disable=SC1090
    source "$SECRETS_FILE"
else
    say "Generating secrets → $SECRETS_FILE"
    # openssl rather than `tr -dc … < /dev/urandom | head -c N`: in that pipeline head exits
    # as soon as it has N bytes, tr takes SIGPIPE, and under `set -o pipefail` the whole
    # command reports failure — so `set -e` kills the script before it writes anything.
    #
    # The "Ss1" prefix is not decoration. Azure rejects an admin password that does not use at
    # least three of {uppercase, lowercase, digit, symbol}, and base64 output, while it almost
    # always contains all three, is not guaranteed to. Prefixing makes it certain.
    DB_PASSWORD="Ss1$(openssl rand -base64 24 | tr -d '/+=')"
    # 64 hex characters — HS256 wants at least 32 bytes of key material.
    JWT_SECRET="$(openssl rand -hex 32)"
    umask 077
    cat > "$SECRETS_FILE" <<EOF
# Generated by azure-setup.sh — git-ignored, never commit.
DB_PASSWORD='$DB_PASSWORD'
JWT_SECRET='$JWT_SECRET'
PG_SERVER='$PG_SERVER'
EOF
fi

# ── resource group ────────────────────────────────────────────────────────
say "Resource group: $RG"
az group create --name "$RG" --location "$LOCATION" -o none

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
        --admin-password "$DB_PASSWORD" \
        --tier Burstable \
        --sku-name Standard_B1ms \
        --storage-size 32 \
        --version 16 \
        --public-access 0.0.0.0 \
        --yes -o none
fi

# 0.0.0.0-0.0.0.0 is Azure's special rule meaning "other Azure services", not "the whole
# internet" — the container app's outbound address is not fixed, so it cannot be listed.
# Everything still needs the password and TLS. Narrowing this to a private VNet is the
# proper answer and costs more; noted rather than done.
#
# -s names the server and -n names the rule; there is no --rule-name, and passing one makes
# the CLI print its help and exit 0 — so this step silently did nothing for a while and was
# only noticed because `--public-access 0.0.0.0` above had already added an equivalent rule
# during server creation. Hence the check afterwards.
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

# ── container apps environment ────────────────────────────────────────────
ENV_URL="$ARM/Microsoft.App/managedEnvironments/$ENVIRONMENT?api-version=$API_VERSION"

if [[ "$(az rest --method get --url "$ENV_URL" --query properties.provisioningState -o tsv 2>/dev/null)" == "Succeeded" ]]; then
    say "Container Apps environment $ENVIRONMENT already exists"
else
    say "Creating Container Apps environment $ENVIRONMENT"
    az rest --method put --url "$ENV_URL" --body '{"location":"'"$LOCATION"'","properties":{}}' -o none
    for _ in $(seq 1 40); do
        state="$(az rest --method get --url "$ENV_URL" --query properties.provisioningState -o tsv 2>/dev/null || true)"
        [[ "$state" == "Succeeded" ]] && break
        [[ "$state" == "Failed" ]] && { echo "environment failed to provision"; exit 1; }
        sleep 15
    done
fi

# ── container app ─────────────────────────────────────────────────────────
# The image must already exist in ghcr.io and be public — Container Apps validates the pull
# while creating, and a create against a missing image leaves the app in Failed with no
# revision at all. Push it first by letting the Deploy workflow run (see deploy/README.md).
say "Container app $APP"

APP_URL="$ARM/Microsoft.App/containerApps/$APP?api-version=$API_VERSION"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

DB_URL="$DB_URL" DB_PASSWORD="$DB_PASSWORD" JWT_SECRET="$JWT_SECRET" \
ENV_ID="/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.App/managedEnvironments/$ENVIRONMENT" \
IMAGE="$IMAGE" LOCATION="$LOCATION" PG_ADMIN="$PG_ADMIN" \
python3 - "$BODY" <<'PY'
import json, os, sys
json.dump({
    "location": os.environ["LOCATION"],
    "properties": {
        "managedEnvironmentId": os.environ["ENV_ID"],
        "configuration": {
            # allowInsecure false: the ingress serves HTTPS and refuses to answer plain HTTP,
            # so a client cannot be talked into sending its device secret in the clear.
            "ingress": {"external": True, "targetPort": 8080,
                        "transport": "auto", "allowInsecure": False},
            # Secrets live here rather than in env values so they are write-only afterwards:
            # a later `show` returns the name, never the value.
            "secrets": [
                {"name": "db-password", "value": os.environ["DB_PASSWORD"]},
                {"name": "jwt-secret",  "value": os.environ["JWT_SECRET"]},
            ],
        },
        "template": {
            "containers": [{
                "name": "api",
                "image": os.environ["IMAGE"],
                "resources": {"cpu": 0.5, "memory": "1Gi"},
                "env": [
                    {"name": "DB_URL",      "value": os.environ["DB_URL"]},
                    {"name": "DB_USER",     "value": os.environ["PG_ADMIN"]},
                    {"name": "DB_PASSWORD", "secretRef": "db-password"},
                    {"name": "JWT_SECRET",  "secretRef": "jwt-secret"},
                ],
            }],
            # min 0 is the whole reason for choosing Container Apps: idle costs nothing. The
            # price is a few seconds of cold start on the first request after a quiet spell.
            "scale": {"minReplicas": 0, "maxReplicas": 2},
        },
    },
}, open(sys.argv[1], "w"))
PY

az rest --method put --url "$APP_URL" --body "@$BODY" -o none

for _ in $(seq 1 40); do
    state="$(az rest --method get --url "$APP_URL" --query properties.provisioningState -o tsv 2>/dev/null || true)"
    case "$state" in
        Succeeded) break ;;
        Failed|Canceled)
            echo "Container app failed to provision. The usual cause is that"
            echo "$IMAGE cannot be pulled — check it exists and the package is public."
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
  Secrets       ${SECRETS_FILE}  (git-ignored)

Next:
  1. curl https://${FQDN}/health          → {"status":"UP","db":"UP"}
  2. Point Unity's BackendConfig.DefaultBaseUrl at https://${FQDN}
  3. Add the GitHub repository secrets from deploy/README.md so CI deploys on every
     green push to main.

If /health says db is DOWN, the first request also had to start the container from zero and
let Flyway migrate an empty database — give it a few seconds and ask again.
EOF
