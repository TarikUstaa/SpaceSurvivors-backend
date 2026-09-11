#!/usr/bin/env bash
#
# Opens a psql session against the DEPLOYED database and closes it again cleanly.
#
#   ./deploy/db-psql.sh                  # interactive prompt
#   ./deploy/db-psql.sh -f query.sql     # run a file
#   echo 'SELECT 1;' | ./deploy/db-psql.sh -   # run stdin
#
# Everything awkward about reaching that database is handled here: the password is read from
# the Key Vault (never stored on this laptop), the firewall is opened for this machine's
# current IP and closed on the way out, and psql comes from a throwaway container so no local
# Postgres install is needed.
#
# Connects as ssadmin — the admin role, because the point of this script is the occasional
# manual look or fix. The running service still connects as the limited ss_app (see
# db-roles.sql); nothing here changes that.
#
# Extracted from db-roles.sh, which did all of this inline for one specific job.

set -euo pipefail

RG="${RG:-spacesurvivors-rg}"
PG_DB="${PG_DB:-spacesurvivors}"
PG_USER="${PG_USER:-ssadmin}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="$HERE/.env.azure"

say() { printf '\033[1m▸ %s\033[0m\n' "$*" >&2; }

command -v az >/dev/null     || { echo "az CLI not found."; exit 1; }
command -v docker >/dev/null || { echo "docker not found (used only to run psql)."; exit 1; }
az account show >/dev/null 2>&1 || { echo "Not logged in. Run: az login"; exit 1; }
[[ -f "$SECRETS_FILE" ]] || { echo "$SECRETS_FILE missing — run azure-setup.sh first."; exit 1; }

# shellcheck disable=SC1090
source "$SECRETS_FILE"
HOST="${PG_SERVER}.postgres.database.azure.com"

say "Reading the admin password from $VAULT"
DB_PASSWORD="$(az keyvault secret show --vault-name "$VAULT" -n pg-admin-password --query value -o tsv)"
[[ -n "$DB_PASSWORD" ]] || { echo "pg-admin-password not found in $VAULT."; exit 1; }

# ── temporary access ──────────────────────────────────────────────────────
# The rule name carries the PID so two concurrent runs cannot delete each other's rule.
RULE="psql-temp-$$"
MYIP="$(curl -fsS https://api.ipify.org)"
cleanup() {
    az postgres flexible-server firewall-rule delete \
        -g "$RG" -s "$PG_SERVER" -n "$RULE" --yes -o none 2>/dev/null || true
    say "firewall rule removed"
}
# Registered before the rule exists, so an interrupt between these two lines still cleans up.
trap cleanup EXIT
say "Opening the database to $MYIP"
az postgres flexible-server firewall-rule create \
    -g "$RG" -s "$PG_SERVER" -n "$RULE" \
    --start-ip-address "$MYIP" --end-ip-address "$MYIP" -o none
sleep 10   # Azure needs a few seconds to apply a new rule

# -i keeps stdin attached: without it a heredoc never reaches psql and a query that ran
# nothing looks exactly like a query that returned nothing.
docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" \
    -v "$HERE:/deploy:ro" \
    postgres:18 psql \
    "host=$HOST port=5432 dbname=$PG_DB user=$PG_USER sslmode=require" "$@"
