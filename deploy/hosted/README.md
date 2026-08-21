# Hosted single-host deployment

This deployment is a fail-closed reference for one Linux host. The only published endpoint is the TLS proxy on
`GEN2SPRING_HTTPS_BIND:8443`. PostgreSQL, MinIO, Web, Worker, Managed Runtime, and both egress services have no host ports.

## Prerequisites

- Linux host with Compose v2 and rootless Docker for the dedicated Worker user
- DNS/TLS certificate and external OIDC client
- host directories owned by the Worker UID: workspaces and sandbox secrets
- files in `GEN2SPRING_SECRET_DIR` with mode `0600`
- digest-pinned generation/import runner images loaded in the rootless daemon

Copy `compose.env.example` to a private environment file and replace every placeholder. Create the secrets named in
`compose.yml`; passwords, OIDC credentials, encryption keys, and TLS key stores must not be placed in the environment
file or committed. The import encryption key is 32 random bytes. Keep prior encryption keys available while encrypted
queued jobs can still reference them.

Set `GEN2SPRING_RUNTIME_BASE_URI` to the externally reachable HTTPS proxy origin returned to MCP clients.

Managed Runtime tokens use `runtime-token-pepper`. Runtime-to-provider traffic uses a dedicated client/server mTLS
pair (`provider-egress-client*` and `provider-egress-server*`). Runtime can reach PostgreSQL and the internal
provider-call network but has no direct external egress; provider-egress has no database, object-storage, OIDC, or
Docker credentials.

## Validate and start

```bash
mise run hosted:config
mise run hosted:up
```

`web` starts only after PostgreSQL migrations, the private MinIO policy, and a recent Worker heartbeat succeed. A
rootless Worker socket is the only Docker socket mounted, and it is never mounted into Web or the proxy. Generation
containers use `network=none`; import containers join only the internal `gen2spring-fetch` network.

## Backup and recovery

Pause mutations before a consistent backup. Back up both PostgreSQL and the MinIO data volume under the same recovery
label; either half alone is insufficient. Store backups outside this host and encrypt them.

```bash
mkdir -p private-backups/2026-08-13
docker compose --env-file deploy/hosted/.env -f deploy/hosted/compose.yml exec -T postgres \
  sh -c 'PGPASSWORD="$$(cat /run/secrets/postgres-password)" pg_dump -U gen2spring -d gen2spring -Fc' \
  > private-backups/2026-08-13/postgres.dump
docker run --rm -v gen2spring-hosted_minio-data:/source:ro \
  -v "$PWD/private-backups/2026-08-13:/backup" \
  debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241 \
  tar -C /source -cf /backup/minio-data.tar .
```

The backup task writes both `postgres.dump` and `minio-data.tar`. Rehearse both into isolated disposable resources:

```bash
GEN2SPRING_POSTGRES_BACKUP="$PWD/private-backups/2026-08-13/postgres.dump" \
GEN2SPRING_MINIO_BACKUP="$PWD/private-backups/2026-08-13/minio-data.tar" \
mise run hosted:restore-rehearsal
```

Never rehearse restore against the active production volumes. After a successful rehearsal, run
`mise run hosted:acceptance` before approving a release.

## Stop

```bash
mise run hosted:down
```

The stop task does not remove volumes. Volume deletion is deliberately not automated.
