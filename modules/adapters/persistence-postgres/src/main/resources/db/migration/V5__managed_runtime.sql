create table managed_runtime_instance (
    id uuid primary key,
    owner_account_id uuid not null,
    catalog_id uuid not null,
    catalog_checksum char(64) not null,
    provider_base_url varchar(4096),
    token_digest bytea not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    constraint managed_runtime_catalog_owner_fk
        foreign key (catalog_id, owner_account_id)
        references tool_catalog(id, owner_account_id) on delete cascade,
    constraint managed_runtime_checksum_valid
        check (catalog_checksum ~ '^[a-f0-9]{64}$'),
    constraint managed_runtime_provider_valid check (
        provider_base_url is null
        or (octet_length(provider_base_url) between 8 and 4096
            and provider_base_url ~ '^https?://')
    ),
    constraint managed_runtime_digest_valid check (octet_length(token_digest) = 32),
    constraint managed_runtime_lifetime_valid check (
        expires_at > created_at
        and expires_at <= created_at + interval '30 days'
    ),
    constraint managed_runtime_revocation_valid
        check (revoked_at is null or revoked_at >= created_at)
);

create index managed_runtime_expiry_idx
    on managed_runtime_instance(expires_at, id)
    where revoked_at is null;
