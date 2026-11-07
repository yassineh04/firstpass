#!/usr/bin/env bash
# Fails if the Terraform configuration declares a resource type that is not listed in
# allowed-resource-types.txt. This enforces the $0 rule: only always-free AWS services.
set -euo pipefail

cd "$(dirname "$0")/.."

allowed_file="allowed-resource-types.txt"
mapfile -t allowed < <(grep -Ev '^[[:space:]]*(#|$)' "$allowed_file")

declared=$(
  grep -rhoE --include='*.tf' --exclude-dir='.terraform' \
    '^[[:space:]]*resource[[:space:]]+"[a-z0-9_]+"' . |
    sed -E 's/.*"([a-z0-9_]+)"/\1/' |
    sort -u
) || true

status=0
count=0
for type in $declared; do
  count=$((count + 1))
  if ! printf '%s\n' "${allowed[@]}" | grep -qxF "$type"; then
    echo "Resource type not allowed by the \$0 rule: $type" >&2
    status=1
  fi
done

echo "Checked $count declared resource type(s) against $allowed_file."
exit "$status"
