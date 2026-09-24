#!/bin/sh
set -eu

read_secret() {
  [ -s "$1" ] || { echo "Garage credential file is unavailable" >&2; exit 2; }
  IFS= read -r value < "$1" || [ -n "$value" ]
  [ -n "$value" ] || { echo "Garage credential file is empty" >&2; exit 2; }
  printf '%s' "$value"
}

access_key=$(read_secret /run/secrets/garage-app-access-key)
secret_key=$(read_secret /run/secrets/garage-app-secret-key)

if ! garage bucket info gen2spring-private >/dev/null 2>&1; then
  garage bucket create gen2spring-private >/dev/null
fi
if ! garage key info "$access_key" >/dev/null 2>&1; then
  garage key import --yes -n gen2spring-app "$access_key" "$secret_key" >/dev/null
else
  stored_secret=$(garage key info --show-secret "$access_key" | awk '$1 == "Secret" && $2 == "key:" {print $3}')
  if [ "$stored_secret" != "$secret_key" ]; then
    echo "Garage credential file does not match the existing access key" >&2
    exit 2
  fi
fi
garage bucket allow --read --write --key "$access_key" gen2spring-private
