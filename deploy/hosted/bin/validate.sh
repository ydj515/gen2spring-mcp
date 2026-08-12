#!/bin/sh
set -eu

ENV_FILE="${GEN2SPRING_HOSTED_ENV_FILE:-deploy/hosted/.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "Hosted configuration is unavailable" >&2
  exit 2
fi
docker compose --env-file "$ENV_FILE" -f deploy/hosted/compose.yml config --quiet
