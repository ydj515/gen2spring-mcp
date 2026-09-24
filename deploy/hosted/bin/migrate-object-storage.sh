#!/bin/sh
set -eu

SECRET_DIR="${GEN2SPRING_MIGRATION_SECRET_DIR:?set GEN2SPRING_MIGRATION_SECRET_DIR}"
case "$SECRET_DIR" in
  /*) ;;
  *) echo "Migration secret directory must be absolute" >&2; exit 2 ;;
esac
for name in minio-root-user minio-root-password garage-app-access-key garage-app-secret-key; do
  [ -s "$SECRET_DIR/$name" ] || { echo "Migration credential file is unavailable: $name" >&2; exit 2; }
done

docker run --rm --network gen2spring-hosted_platform \
  -v "$SECRET_DIR:/run/migration-secrets:ro" \
  -e GEN2SPRING_MIGRATION_RESUME \
  --entrypoint /bin/sh \
  rclone/rclone:1.75.1@sha256:45401ad7410db1d67ffdb58e19059ad20b0d8e0285a60e38bbec55cc1019c7a5 \
  -euc '
    export RCLONE_CONFIG_LEGACY_TYPE=s3
    export RCLONE_CONFIG_LEGACY_PROVIDER=Minio
    export RCLONE_CONFIG_LEGACY_ENV_AUTH=false
    export RCLONE_CONFIG_LEGACY_ACCESS_KEY_ID="$(cat /run/migration-secrets/minio-root-user)"
    export RCLONE_CONFIG_LEGACY_SECRET_ACCESS_KEY="$(cat /run/migration-secrets/minio-root-password)"
    export RCLONE_CONFIG_LEGACY_ENDPOINT=http://minio:9000
    export RCLONE_CONFIG_LEGACY_REGION=us-east-1
    export RCLONE_CONFIG_TARGET_TYPE=s3
    export RCLONE_CONFIG_TARGET_PROVIDER=Other
    export RCLONE_CONFIG_TARGET_ENV_AUTH=false
    export RCLONE_CONFIG_TARGET_ACCESS_KEY_ID="$(cat /run/migration-secrets/garage-app-access-key)"
    export RCLONE_CONFIG_TARGET_SECRET_ACCESS_KEY="$(cat /run/migration-secrets/garage-app-secret-key)"
    export RCLONE_CONFIG_TARGET_ENDPOINT=http://garage:3900
    export RCLONE_CONFIG_TARGET_REGION=garage
    target_listing=$(rclone lsf -R --files-only target:gen2spring-private)
    if [ -n "$target_listing" ] && [ "${GEN2SPRING_MIGRATION_RESUME:-}" != yes ]; then
      echo "Garage bucket is not empty; set GEN2SPRING_MIGRATION_RESUME=yes to retry a partial copy" >&2
      exit 2
    fi
    rclone copy --metadata legacy:gen2spring-private target:gen2spring-private
    rclone check --download legacy:gen2spring-private target:gen2spring-private
  '
