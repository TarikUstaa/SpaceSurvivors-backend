#!/usr/bin/env bash
#
# Applies deploy/db-roles.sql to the deployed database, then wires the container app to use
# the two roles it creates. Run after azure-setup.sh, from a machine logged in with `az`.
#
# The database only admits connections from inside Azure, so this opens a firewall rule for
# the machine running it and closes it again on the way out — including when it fails, which
# is when leaving one open would matter most.
#
# psql comes from a throwaway postgres container rather than the host, so this needs Docker
# but not a local Postgres install.
#
# Idempotent: the SQL resets passwords and re-applies grants without dropping anything, and
# the two role passwords are kept in deploy/.env.azure so a re-run does not invalidate the
# credentials the running service is already using.

set -euo pipefail

RG="${RG:-spacesurvivors-rg}"
APP="${APP:-spacesurvivors-api}"
PG_DB="spacesurvivors"
API_VERSION="2024-03-01"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="$HERE/.env.azure"

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

command -v az >/dev/null     || { echo "az CLI not found."; exit 1; }
command -v docker >/dev/null || { echo "docker not found (used only to run psql)."; exit 1; }
az account show >/dev/null 2>&1 || { echo "Not logged in. Run: az login"; exit 1; }
[[ -f "$SECRETS_FILE" ]] || { echo "$SECRETS_FILE missing — run azure-setup.sh first."; exit 1; }

# shellcheck disable=SC1090
source "$SECRETS_FILE"
SUB="$(az account show --query id -o tsv)"
HOST="${PG_SERVER}.postgres.database.azure.com"

# ── role passwords ────────────────────────────────────────────────────────
# Same reasoning as azure-setup.sh: generated once, reused forever. Rotating them here would
# leave the running revision holding credentials that no longer work.
if [[ -z "${APP_DB_PASSWORD:-}" || -z "${MIGRATE_DB_PASSWORD:-}" ]]; then
    say "Generating role passwords → $SECRETS_FILE"
    APP_DB_PASSWORD="Ss1$(openssl rand -base64 24 | tr -d '/+=')"
    MIGRATE_DB_PASSWORD="Ss1$(openssl rand -base64 24 | tr -d '/+=')"
    umask 077
    cat >> "$SECRETS_FILE" <<EOF
APP_DB_PASSWORD='$APP_DB_PASSWORD'
MIGRATE_DB_PASSWORD='$MIGRATE_DB_PASSWORD'
EOF
else
    say "Reusing role passwords from $SECRETS_FILE"
fi

# ── temporary access ──────────────────────────────────────────────────────
MYIP="$(curl -fsS https://api.ipify.org)"
say "Opening the database to $MYIP (removed when this exits)"
# The trap is registered before the rule is created, so an interrupt between the two lines
# still runs the cleanup — deleting a rule that does not exist is not an error worth failing on.
cleanup() {
    az postgres flexible-server firewall-rule delete \
        -g "$RG" -s "$PG_SERVER" -n db-roles-temp --yes -o none 2>/dev/null || true
    echo "temporary firewall rule removed"
}
trap cleanup EXIT
az postgres flexible-server firewall-rule create \
    -g "$RG" -s "$PG_SERVER" -n db-roles-temp \
    --start-ip-address "$MYIP" --end-ip-address "$MYIP" -o none

# Azure takes a few seconds to apply a new rule.
sleep 10

# ── apply ─────────────────────────────────────────────────────────────────
say "Applying db-roles.sql"
docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" \
    -v "$HERE/db-roles.sql:/db-roles.sql:ro" \
    postgres:18 psql \
    "host=$HOST port=5432 dbname=$PG_DB user=ssadmin sslmode=require" \
    -v migrate_pw="$MIGRATE_DB_PASSWORD" \
    -v app_pw="$APP_DB_PASSWORD" \
    -f /db-roles.sql

# ── prove the limits are real ─────────────────────────────────────────────
# Granting the right privileges and having them take effect are different claims, and only
# the second one matters. So connect as ss_app and check it can do its job and cannot exceed it.
say "Checking what ss_app can actually do"
# -i, or the heredoc never reaches psql's stdin and this prints nothing at all — which reads
# exactly like a check that passed.
docker run --rm -i -e PGPASSWORD="$APP_DB_PASSWORD" postgres:18 psql \
    "host=$HOST port=5432 dbname=$PG_DB user=ss_app sslmode=require" -At <<'SQL'
SELECT 'read player_profile: ' || count(*) FROM player_profile;
SELECT 'can drop a table:    ' ||
       has_table_privilege('ss_app', 'public.player_profile', 'TRUNCATE')::text;
SELECT 'can create a table:  ' || has_schema_privilege('ss_app', 'public', 'CREATE')::text;
SELECT 'can read the ledger: ' ||
       has_table_privilege('ss_app', 'public.flyway_schema_history', 'SELECT')::text;
SQL

say "Done"
cat <<EOF

ss_migrate and ss_app exist and the grants are in place.

The container app is NOT switched over by this script, because the running image has to
understand the split first — application-prod.properties has to name FLYWAY_USER and
FLYWAY_PASSWORD, and an older image handed DB_USER=ss_app would try to run Flyway as an
account that cannot write the migration ledger, and crash-loop.

The order that avoids that:

  1. Set FLYWAY_USER / FLYWAY_PASSWORD on the app while DB_USER is still ssadmin.
     The current image ignores them.
  2. Deploy the image that reads them. It migrates as ss_migrate, still queries as ssadmin.
  3. Switch DB_USER / DB_PASSWORD to ss_app.

Passwords for both roles are in $SECRETS_FILE.
EOF
