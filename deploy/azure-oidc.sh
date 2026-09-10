#!/usr/bin/env bash
#
# Lets the Deploy workflow sign in to Azure without a stored password.
#
# It registers an identity, allows it to act on this one resource group, and tells Azure to
# trust tokens GitHub mints for this repository's main branch. Afterwards the workflow
# authenticates by proving who it is, and Azure hands back a token that lives minutes — so
# there is no client secret in the repository to leak, rotate, or notice the theft of. That
# matters more here than usual: a secret with Contributor on the group would be a key to
# every resource in it, including the database.
#
# Idempotent; run it after `az login`. It prints the values to paste into GitHub at the end,
# and prints no secret, because with OIDC there is not one.

set -euo pipefail

RG="${RG:-spacesurvivors-rg}"
APP_NAME="${APP_NAME:-spacesurvivors-deploy}"
REPO="${REPO:-TarikUstaa/SpaceSurvivors-backend}"
BRANCH="${BRANCH:-main}"

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

command -v az >/dev/null || { echo "az CLI not found."; exit 1; }
az account show >/dev/null 2>&1 || { echo "Not logged in. Run: az login"; exit 1; }

SUB="$(az account show --query id -o tsv)"
TENANT="$(az account show --query tenantId -o tsv)"

say "Application registration: $APP_NAME"
APP_ID="$(az ad app list --display-name "$APP_NAME" --query "[0].appId" -o tsv 2>/dev/null || true)"
if [[ -z "$APP_ID" || "$APP_ID" == "None" ]]; then
    APP_ID="$(az ad app create --display-name "$APP_NAME" --query appId -o tsv)"
    echo "created"
else
    echo "already exists"
fi
echo "appId: $APP_ID"

say "Service principal"
# The registration is the identity; the service principal is that identity's presence in this
# tenant, and it is the thing a role can actually be assigned to.
az ad sp show --id "$APP_ID" >/dev/null 2>&1 || az ad sp create --id "$APP_ID" -o none
echo "ok"

say "Contributor on resource group $RG"
# Scoped to the group, not the subscription: this identity should be able to roll out a new
# revision of one container app, not to create resources anywhere it likes.
az role assignment create \
    --assignee "$APP_ID" \
    --role Contributor \
    --scope "/subscriptions/$SUB/resourceGroups/$RG" \
    -o none 2>/dev/null || echo "(already assigned)"
echo "ok"

say "Federated credential for $REPO@$BRANCH"
# `subject` is the security boundary. A token GitHub mints for another repository, or for a
# branch other than this one, does not match and is refused — so a fork or a pull request
# cannot deploy, no matter what its workflow file says.
if az ad app federated-credential list --id "$APP_ID" --query "[?name=='github-$BRANCH']" -o tsv | grep -q .; then
    echo "already exists"
else
    az ad app federated-credential create --id "$APP_ID" --parameters "{
        \"name\": \"github-$BRANCH\",
        \"issuer\": \"https://token.actions.githubusercontent.com\",
        \"subject\": \"repo:$REPO:ref:refs/heads/$BRANCH\",
        \"audiences\": [\"api://AzureADTokenExchange\"]
    }" -o none
    echo "created"
fi

say "Add these five repository secrets"
cat <<EOF

  GitHub → Settings → Secrets and variables → Actions → New repository secret

  AZURE_CLIENT_ID          $APP_ID
  AZURE_TENANT_ID          $TENANT
  AZURE_SUBSCRIPTION_ID    $SUB
  AZURE_RG                 $RG
  AZURE_APP                spacesurvivors-api

None of these is a secret in the sense of a password — they are identifiers. What makes the
arrangement safe is the federated credential above, which is why nothing here needs guarding
the way a client secret would.
EOF
