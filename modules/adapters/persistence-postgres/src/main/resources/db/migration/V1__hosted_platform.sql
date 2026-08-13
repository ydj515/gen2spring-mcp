create table account (
    id uuid primary key,
    issuer varchar(2048) not null,
    subject varchar(512) not null,
    created_at timestamptz not null,
    last_seen_at timestamptz not null,
    constraint account_external_identity_unique unique (issuer, subject),
    constraint account_issuer_valid check (issuer <> ''),
    constraint account_subject_valid check (subject <> ''),
    constraint account_last_seen_valid check (last_seen_at >= created_at)
);

create table specification (
    id uuid primary key,
    owner_account_id uuid not null references account(id) on delete cascade,
    source_type varchar(16) not null,
    object_key varchar(512) not null,
    sha256 char(64) not null,
    byte_size bigint not null,
    display_label varchar(160) not null,
    parse_state varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint specification_source_type_valid check (source_type in ('UPLOAD', 'URL')),
    constraint specification_object_key_valid check (object_key <> ''),
    constraint specification_sha256_valid check (sha256 ~ '^[a-f0-9]{64}$'),
    constraint specification_byte_size_valid check (byte_size between 0 and 10485760),
    constraint specification_display_label_valid check (display_label <> ''),
    constraint specification_parse_state_valid check (parse_state in ('PENDING', 'READY', 'FAILED')),
    constraint specification_timestamp_valid check (updated_at >= created_at),
    constraint specification_owner_identity unique (id, owner_account_id)
);

create index specification_owner_created_idx
    on specification(owner_account_id, created_at desc, id desc);

create table generation_job (
    id uuid primary key,
    owner_account_id uuid not null references account(id) on delete cascade,
    specification_id uuid,
    kind varchar(24) not null,
    operation varchar(64) not null,
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    request_snapshot jsonb not null,
    status varchar(16) not null,
    stage varchar(64),
    attempt integer not null default 0,
    lease_owner varchar(64),
    lease_until timestamptz,
    fencing_token bigint not null default 0,
    cancel_requested boolean not null default false,
    safe_error_code varchar(64),
    safe_error_summary varchar(256),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint generation_job_idempotency_unique
        unique (owner_account_id, operation, idempotency_key),
    constraint generation_job_owner_identity unique (id, owner_account_id),
    constraint generation_job_specification_owner_fk
        foreign key (specification_id, owner_account_id)
        references specification(id, owner_account_id),
    constraint generation_job_kind_valid check (kind in ('SPEC_IMPORT', 'GENERATION')),
    constraint generation_job_specification_valid check (
        (kind = 'SPEC_IMPORT' and specification_id is null)
        or (kind = 'GENERATION' and specification_id is not null)
    ),
    constraint generation_job_operation_valid check (operation ~ '^[a-z][a-z0-9-]{0,63}$'),
    constraint generation_job_idempotency_key_valid
        check (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    constraint generation_job_request_hash_valid check (request_hash ~ '^[a-f0-9]{64}$'),
    constraint generation_job_request_size_valid
        check (octet_length(request_snapshot::text) between 2 and 1048576),
    constraint generation_job_status_valid
        check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    constraint generation_job_stage_valid
        check (stage is null or stage ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint generation_job_attempt_valid check (attempt >= 0),
    constraint generation_job_fencing_token_valid check (fencing_token >= 0),
    constraint generation_job_lease_valid check (
        (status = 'RUNNING' and lease_owner is not null and lease_until is not null and fencing_token > 0)
        or (status <> 'RUNNING' and lease_owner is null and lease_until is null)
    ),
    constraint generation_job_safe_error_valid check (
        (status in ('QUEUED', 'RUNNING', 'SUCCEEDED')
            and safe_error_code is null and safe_error_summary is null)
        or (status in ('FAILED', 'CANCELLED')
            and safe_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
            and safe_error_summary is not null and safe_error_summary <> '')
    ),
    constraint generation_job_timestamp_valid check (updated_at >= created_at),
    constraint generation_job_version_valid check (version >= 0)
);

create index generation_job_owner_created_idx
    on generation_job(owner_account_id, created_at desc, id desc);
create index generation_job_owner_status_idx
    on generation_job(owner_account_id, status);
create index generation_job_claim_idx
    on generation_job(status, created_at, id)
    where status = 'QUEUED';
create index generation_job_lease_idx
    on generation_job(lease_until, id)
    where status = 'RUNNING';

create table generation_job_event (
    job_id uuid not null references generation_job(id) on delete cascade,
    sequence bigint not null,
    from_status varchar(16),
    to_status varchar(16) not null,
    stage varchar(64),
    safe_code varchar(64),
    safe_summary varchar(256),
    created_at timestamptz not null,
    primary key (job_id, sequence),
    constraint generation_job_event_sequence_valid check (sequence > 0),
    constraint generation_job_event_from_status_valid check (
        from_status is null or from_status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    constraint generation_job_event_to_status_valid check (
        to_status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    constraint generation_job_event_stage_valid
        check (stage is null or stage ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint generation_job_event_safe_code_valid
        check (safe_code is null or safe_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint generation_job_event_safe_summary_valid
        check (safe_summary is null or safe_summary <> '')
);

create table artifact (
    id uuid primary key,
    job_id uuid not null,
    owner_account_id uuid not null,
    type varchar(64) not null,
    object_key varchar(512) not null,
    sha256 char(64) not null,
    byte_size bigint not null,
    content_type varchar(128) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    constraint artifact_job_owner_fk
        foreign key (job_id, owner_account_id)
        references generation_job(id, owner_account_id) on delete cascade,
    constraint artifact_type_valid check (type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint artifact_object_key_valid check (object_key <> ''),
    constraint artifact_sha256_valid check (sha256 ~ '^[a-f0-9]{64}$'),
    constraint artifact_byte_size_valid check (byte_size >= 0),
    constraint artifact_content_type_valid check (content_type <> ''),
    constraint artifact_expiry_valid check (expires_at > created_at)
);

create index artifact_owner_created_idx
    on artifact(owner_account_id, created_at desc, id desc);
create index artifact_expiry_idx on artifact(expires_at);
