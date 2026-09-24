#!/bin/sh
set -eu

ENV_FILE="${GEN2SPRING_HOSTED_ENV_FILE:-deploy/hosted/.env}"
DESTINATION="${GEN2SPRING_BACKUP_DIRECTORY:?set GEN2SPRING_BACKUP_DIRECTORY}"
[ -f "$ENV_FILE" ] || { echo "Hosted configuration is unavailable" >&2; exit 2; }
case "$DESTINATION" in
  /*) ;;
  *) echo "Backup directory must be absolute" >&2; exit 2 ;;
esac
if [ -e "$DESTINATION" ]; then
  echo "Backup directory already exists" >&2
  exit 2
fi
running_services=$(docker compose --env-file "$ENV_FILE" -f deploy/hosted/compose.yml ps --status running --services)
if printf '%s\n' "$running_services" | grep -Eq '^(web|worker)$'; then
  echo "Stop Web and Worker for the backup maintenance window" >&2
  exit 2
fi
umask 077
mkdir -p "$DESTINATION"
docker compose --env-file "$ENV_FILE" -f deploy/hosted/compose.yml exec -T postgres \
  sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" pg_dump -U gen2spring -d gen2spring -Fc' \
  > "$DESTINATION/postgres.dump"
test -s "$DESTINATION/postgres.dump"
docker compose --env-file "$ENV_FILE" -f deploy/hosted/compose.yml stop garage
restart_garage() {
  docker compose --env-file "$ENV_FILE" -f deploy/hosted/compose.yml up -d --wait garage
}
trap restart_garage EXIT
docker run --rm \
  -v gen2spring-hosted_garage-data:/source:ro \
  debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241 \
  tar -C /source -cf - . > "$DESTINATION/garage-data.tar"
test -s "$DESTINATION/garage-data.tar"
restart_garage
trap - EXIT
echo "Hosted backup created"
