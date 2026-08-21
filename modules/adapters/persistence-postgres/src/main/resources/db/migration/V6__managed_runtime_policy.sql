alter table managed_runtime_instance
    add constraint managed_runtime_id_owner_unique unique (id, owner_account_id);

create table managed_credential (
    id uuid primary key,
    owner_account_id uuid not null references account(id) on delete cascade,
    label varchar(128) not null,
    kind varchar(16) not null,
    credential_version bigint not null,
    envelope_version integer not null,
    key_id varchar(64) not null,
    wrapped_key_nonce bytea not null,
    wrapped_key bytea not null,
    payload_nonce bytea not null,
    ciphertext bytea not null,
    created_at timestamptz not null,
    rotated_at timestamptz not null,
    revoked_at timestamptz,
    constraint managed_credential_id_owner_unique unique (id, owner_account_id),
    constraint managed_credential_label_valid check (
        octet_length(label) between 1 and 128 and label !~ '[[:cntrl:]]'),
    constraint managed_credential_kind_valid check (kind in ('OPAQUE', 'BEARER', 'BASIC')),
    constraint managed_credential_version_valid check (credential_version >= 1),
    constraint managed_credential_envelope_valid check (
        envelope_version = 1
        and key_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
        and octet_length(wrapped_key_nonce) = 12
        and octet_length(wrapped_key) = 48
        and octet_length(payload_nonce) = 12
        and octet_length(ciphertext) between 17 and 12500),
    constraint managed_credential_times_valid check (
        rotated_at >= created_at and (revoked_at is null or revoked_at >= created_at))
);

create index managed_credential_owner_created_idx
    on managed_credential(owner_account_id, created_at desc, id);

create table managed_runtime_credential_binding (
    runtime_id uuid not null,
    owner_account_id uuid not null,
    credential_slot varchar(128) not null,
    credential_id uuid not null,
    credential_version bigint not null,
    primary key (runtime_id, credential_slot),
    constraint managed_runtime_binding_slot_valid
        check (credential_slot ~ '^[a-z][a-z0-9_-]{0,127}$'),
    constraint managed_runtime_binding_version_valid check (credential_version >= 1),
    constraint managed_runtime_binding_runtime_owner_fk
        foreign key (runtime_id, owner_account_id)
        references managed_runtime_instance(id, owner_account_id) on delete cascade,
    constraint managed_runtime_binding_credential_owner_fk
        foreign key (credential_id, owner_account_id)
        references managed_credential(id, owner_account_id)
);

create table managed_runtime_grant (
    id uuid primary key,
    runtime_id uuid not null,
    owner_account_id uuid not null,
    principal varchar(128) not null,
    allowed_tools text[] not null,
    requests_per_minute integer not null,
    token_digest bytea not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    constraint managed_runtime_grant_identity_unique unique (id, runtime_id, owner_account_id),
    constraint managed_runtime_grant_runtime_owner_fk
        foreign key (runtime_id, owner_account_id)
        references managed_runtime_instance(id, owner_account_id) on delete cascade,
    constraint managed_runtime_grant_principal_valid
        check (principal ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    constraint managed_runtime_grant_tools_valid check (cardinality(allowed_tools) between 1 and 1024),
    constraint managed_runtime_grant_rate_valid check (requests_per_minute between 1 and 6000),
    constraint managed_runtime_grant_digest_valid check (octet_length(token_digest) = 32),
    constraint managed_runtime_grant_lifetime_valid check (
        expires_at > created_at and expires_at <= created_at + interval '30 days'),
    constraint managed_runtime_grant_revocation_valid check (revoked_at is null or revoked_at >= created_at)
);

create index managed_runtime_grant_runtime_idx
    on managed_runtime_grant(runtime_id, created_at desc, id);

create table managed_runtime_rate_window (
    runtime_id uuid not null references managed_runtime_instance(id) on delete cascade,
    access_key varchar(36) not null,
    window_start timestamptz not null,
    request_count integer not null,
    primary key (runtime_id, access_key),
    constraint managed_runtime_rate_key_valid check (
        access_key = 'owner' or access_key ~ '^[a-f0-9-]{36}$'),
    constraint managed_runtime_rate_count_valid check (request_count >= 1)
);

create table managed_tool_execution_audit (
    execution_id uuid primary key,
    owner_account_id uuid not null,
    runtime_id uuid not null,
    grant_id uuid,
    principal varchar(128) not null,
    catalog_checksum char(64) not null,
    tool_name varchar(128) not null,
    status varchar(24) not null,
    error_category varchar(64),
    provider_status integer,
    duration_millis bigint not null,
    request_bytes bigint not null,
    response_bytes bigint not null,
    started_at timestamptz not null,
    completed_at timestamptz,
    constraint managed_audit_runtime_owner_fk
        foreign key (runtime_id, owner_account_id)
        references managed_runtime_instance(id, owner_account_id) on delete cascade,
    constraint managed_audit_grant_owner_fk
        foreign key (grant_id, runtime_id, owner_account_id)
        references managed_runtime_grant(id, runtime_id, owner_account_id),
    constraint managed_audit_principal_valid
        check (principal ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    constraint managed_audit_checksum_valid check (catalog_checksum ~ '^[a-f0-9]{64}$'),
    constraint managed_audit_tool_valid check (tool_name ~ '^[a-z][a-z0-9_]{0,127}$'),
    constraint managed_audit_status_valid check (
        status in ('STARTED', 'SUCCEEDED', 'TOOL_ERROR', 'RATE_LIMITED', 'INTERNAL_ERROR')),
    constraint managed_audit_category_valid check (
        error_category is null or error_category ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint managed_audit_provider_status_valid check (
        provider_status is null or provider_status between 100 and 599),
    constraint managed_audit_measurements_valid check (
        duration_millis between 0 and 86400000
        and request_bytes between 0 and 1048576
        and response_bytes between 0 and 1048576),
    constraint managed_audit_completion_valid check (
        (status = 'STARTED' and completed_at is null and error_category is null
            and provider_status is null and duration_millis = 0
            and request_bytes = 0 and response_bytes = 0)
        or (status = 'SUCCEEDED' and completed_at is not null and completed_at >= started_at
            and error_category is null and provider_status between 200 and 299)
        or (status = 'TOOL_ERROR' and completed_at is not null and completed_at >= started_at
            and error_category is not null)
        or (status = 'RATE_LIMITED' and completed_at is not null and completed_at >= started_at
            and error_category = 'RATE_LIMITED' and provider_status is null)
        or (status = 'INTERNAL_ERROR' and completed_at is not null and completed_at >= started_at
            and error_category = 'INTERNAL_ERROR' and provider_status is null))
);

create index managed_audit_owner_runtime_page_idx
    on managed_tool_execution_audit(owner_account_id, runtime_id, started_at desc, execution_id desc);
