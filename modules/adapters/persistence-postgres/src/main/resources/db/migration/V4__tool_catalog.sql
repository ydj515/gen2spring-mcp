create table tool_catalog (
    id uuid primary key,
    owner_account_id uuid not null,
    generation_job_id uuid not null,
    metadata_version varchar(16) not null,
    specification_checksum char(64) not null,
    metadata_checksum char(64) not null,
    metadata_document text not null,
    tool_count integer not null,
    created_at timestamptz not null,
    constraint tool_catalog_owner_identity unique (id, owner_account_id),
    constraint tool_catalog_generation_owner_fk
        foreign key (generation_job_id, owner_account_id)
        references generation_job(id, owner_account_id) on delete cascade,
    constraint tool_catalog_generation_unique unique (generation_job_id),
    constraint tool_catalog_metadata_version_valid
        check (metadata_version ~ '^[0-9]+\.[0-9]+$'),
    constraint tool_catalog_specification_checksum_valid
        check (specification_checksum ~ '^[a-f0-9]{64}$'),
    constraint tool_catalog_metadata_checksum_valid
        check (metadata_checksum ~ '^[a-f0-9]{64}$'),
    constraint tool_catalog_metadata_document_valid check (
        octet_length(metadata_document) between 2 and 1048576
        and jsonb_typeof(metadata_document::jsonb) = 'object'
    ),
    constraint tool_catalog_tool_count_valid check (tool_count between 1 and 1000)
);

create index tool_catalog_owner_created_idx
    on tool_catalog(owner_account_id, created_at desc, id desc);

create table tool_catalog_entry (
    catalog_id uuid not null references tool_catalog(id) on delete cascade,
    ordinal integer not null,
    tool_name varchar(128) not null,
    operation_id varchar(128) not null,
    metadata_document text not null,
    primary key (catalog_id, tool_name),
    constraint tool_catalog_entry_ordinal_unique unique (catalog_id, ordinal),
    constraint tool_catalog_entry_ordinal_valid check (ordinal between 0 and 999),
    constraint tool_catalog_entry_name_valid check (tool_name ~ '^[a-z][a-z0-9_]{0,127}$'),
    constraint tool_catalog_entry_operation_valid check (
        octet_length(operation_id) between 1 and 128
        and operation_id !~ '[[:cntrl:]]'
    ),
    constraint tool_catalog_entry_document_valid check (
        octet_length(metadata_document) between 2 and 1048576
        and jsonb_typeof(metadata_document::jsonb) = 'object'
    )
);
