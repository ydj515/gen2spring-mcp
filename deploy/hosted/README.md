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
file or committed. The import target key and both Managed Runtime credential keys are independent 32-byte random
values. `credential-key-active` encrypts new credential versions; `credential-key-retired` remains mounted read-only
while any stored credential still references its key ID.

Set `GEN2SPRING_RUNTIME_BASE_URI` to the externally reachable HTTPS proxy origin returned to MCP clients.

Managed Runtime tokens use `runtime-token-pepper`. Runtime-to-provider traffic uses a dedicated client/server mTLS
pair (`provider-egress-client*` and `provider-egress-server*`). Runtime can reach PostgreSQL and the internal
provider-call network but has no direct external egress; provider-egress has no database, object-storage, OIDC, or
Docker credentials.

Managed Runtime transport is stateless. Scale it without publishing a runtime host port or configuring proxy affinity:

```bash
docker compose --env-file deploy/hosted/.env -f deploy/hosted/compose.yml up -d --scale runtime=2
```

The proxy may send consecutive MCP requests to different replicas. PostgreSQL is the correctness source for runtime
and grant revocation, Catalog transitions, rate windows, and audit state; the local SDK handle cache contains no bearer
or credential value. A migrated Catalog checksum causes each replica to replace its stale handle on its next request;
sticky routing is neither required nor supported.

## V7 Catalog version rollout

Before the first V7-capable deployment, pause generation, Runtime migration, grant mutation, and credential mutation,
then create one consistent PostgreSQL and MinIO backup as described below. V7 backfills every existing Catalog as an
independent revision-1 family and creates append-only `managed_runtime_catalog_transition` history. Deploy Web, Worker,
and Runtime artifacts built from the same commit, allow Flyway to reach V7, and then run `mise run hosted:acceptance`
before reopening mutations.

Do not down-migrate V7 or manually delete family and transition rows. If rollout verification fails and the previous
binary must be restored, stop every application process and restore both PostgreSQL and MinIO from the same pre-V7
recovery label. A PostgreSQL-only rollback can leave generation artifacts and Catalog state inconsistent.

```sql
select version, success from flyway_schema_history where version = '7';
select count(*) from tool_catalog where revision = 1 and family_id = id;
select count(*) from managed_runtime_catalog_transition;
```

## Credential key rotation

Create a new 32-byte file with mode `0600`. Add a distinct Compose secret, mount, and
`GEN2SPRING_*_CREDENTIAL_ENCRYPTION_KEY_FILES_<KEY_ID>` mapping for the new ID in both `web` and `runtime`; retain one
mapping and immutable key file for every ID still returned by `select distinct key_id from managed_credential`.
Change `GEN2SPRING_CREDENTIAL_ACTIVE_KEY_ID`, validate the rendered Compose configuration, then restart `web` and every
`runtime` replica. Rotate each stored credential through the credential API so its new version uses the active key.
Before removing an old mapping, verify that the query below returns zero; remove the mount, mapping, and old file only
after that verification. Never overwrite a key file in place or reuse a key ID for different bytes.

```sql
select count(*) from managed_credential where key_id = '<retired-key-id>';
```

## Audit retention

Back up PostgreSQL first. Reconcile old `STARTED` rows separately, then delete only completed audit rows older than the
approved retention period. For example, a 90-day policy uses the following bounded operation during a maintenance
window; repeat it until it reports zero rows rather than running an unbounded delete:

```sql
delete from managed_tool_execution_audit
where execution_id in (
  select execution_id
  from managed_tool_execution_audit
  where completed_at < now() - interval '90 days'
  order by completed_at
  limit 10000
);
```

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
