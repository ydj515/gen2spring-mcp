#!/bin/sh
set -eu

BACKUP="${GEN2SPRING_POSTGRES_BACKUP:?set GEN2SPRING_POSTGRES_BACKUP}"
MINIO_BACKUP="${GEN2SPRING_MINIO_BACKUP:?set GEN2SPRING_MINIO_BACKUP}"
case "$BACKUP" in
  /*) ;;
  *) echo "Backup path must be absolute" >&2; exit 2 ;;
esac
if [ ! -f "$BACKUP" ] || [ ! -s "$BACKUP" ]; then
  echo "Backup is unavailable" >&2
  exit 2
fi
if [ ! -f "$MINIO_BACKUP" ] || [ ! -s "$MINIO_BACKUP" ]; then
  echo "MinIO backup is unavailable" >&2
  exit 2
fi
NAME="gen2spring-restore-$$"
VOLUME="$NAME-minio"
cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
docker run -d --rm --name "$NAME" \
  -e POSTGRES_DB=gen2spring -e POSTGRES_USER=gen2spring -e POSTGRES_PASSWORD=rehearsal-only \
  postgres:17.9-alpine@sha256:c7526c0f6c3f30260a563d7bcf8ad778effac59a44f8ffa86678c35418338609 >/dev/null
i=0
until docker exec "$NAME" pg_isready -U gen2spring -d gen2spring >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -le 30 ] || exit 5; sleep 1
done
docker cp "$BACKUP" "$NAME:/tmp/postgres.dump"
docker exec -e PGPASSWORD=rehearsal-only "$NAME" \
  pg_restore --exit-on-error --clean --if-exists -U gen2spring -d gen2spring /tmp/postgres.dump >/dev/null
docker exec -e PGPASSWORD=rehearsal-only "$NAME" \
  psql -U gen2spring -d gen2spring -Atc "select count(*) from flyway_schema_history" | grep -Eq '^[1-9][0-9]*$'
docker volume create "$VOLUME" >/dev/null
docker run --rm -i -v "$VOLUME:/restore" \
  debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241 \
  tar -C /restore -xf - < "$MINIO_BACKUP"
docker run --rm -v "$VOLUME:/restore:ro" \
  debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241 \
  test -d /restore/.minio.sys
echo "Hosted restore rehearsal succeeded"
