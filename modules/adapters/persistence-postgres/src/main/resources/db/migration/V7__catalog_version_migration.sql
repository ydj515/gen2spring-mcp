create table tool_catalog_family (
    id uuid primary key,
    owner_account_id uuid not null references account(id) on delete cascade,
    head_catalog_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint tool_catalog_family_owner_identity unique (id, owner_account_id),
    constraint tool_catalog_family_timestamp_valid check (updated_at >= created_at)
);

alter table generation_job
    add column predecessor_catalog_id uuid;

alter table tool_catalog
    add column family_id uuid,
    add column revision bigint,
    add column predecessor_catalog_id uuid;

insert into tool_catalog_family(
    id, owner_account_id, head_catalog_id, created_at, updated_at)
select id, owner_account_id, null, created_at, created_at
  from tool_catalog;

update tool_catalog
   set family_id = id,
       revision = 1;

update tool_catalog_family family
   set head_catalog_id = catalog.id
  from tool_catalog catalog
 where catalog.family_id = family.id;

alter table tool_catalog
    alter column family_id set not null,
    alter column revision set not null,
    add constraint tool_catalog_family_identity_unique
        unique (id, owner_account_id, family_id),
    add constraint tool_catalog_family_revision_unique
        unique (family_id, revision),
    add constraint tool_catalog_family_owner_fk
        foreign key (family_id, owner_account_id)
        references tool_catalog_family(id, owner_account_id) on delete cascade,
    add constraint tool_catalog_predecessor_family_fk
        foreign key (predecessor_catalog_id, owner_account_id, family_id)
        references tool_catalog(id, owner_account_id, family_id),
    add constraint tool_catalog_revision_shape_valid check (
        (revision = 1 and predecessor_catalog_id is null and family_id = id)
        or (revision > 1 and predecessor_catalog_id is not null and family_id <> id)
    );

alter table tool_catalog_family
    add constraint tool_catalog_family_head_fk
        foreign key (head_catalog_id, owner_account_id, id)
        references tool_catalog(id, owner_account_id, family_id) on delete cascade;

alter table generation_job
    add constraint generation_job_predecessor_owner_fk
        foreign key (predecessor_catalog_id, owner_account_id)
        references tool_catalog(id, owner_account_id);

create index tool_catalog_family_owner_updated_idx
    on tool_catalog_family(owner_account_id, updated_at desc, id);

create index generation_job_predecessor_idx
    on generation_job(predecessor_catalog_id)
    where predecessor_catalog_id is not null;

create or replace function prepare_root_tool_catalog()
returns trigger
language plpgsql
as $$
begin
    if new.family_id is null then
        if new.revision is not null or new.predecessor_catalog_id is not null then
            raise exception 'Incomplete Tool Catalog lineage';
        end if;
        new.family_id := new.id;
        new.revision := 1;
        insert into tool_catalog_family(
            id, owner_account_id, head_catalog_id, created_at, updated_at)
        values (new.id, new.owner_account_id, null, new.created_at, new.created_at);
    end if;
    return new;
end;
$$;

create trigger tool_catalog_prepare_root
before insert on tool_catalog
for each row execute function prepare_root_tool_catalog();

create or replace function advance_root_tool_catalog_family()
returns trigger
language plpgsql
as $$
begin
    if new.revision = 1 then
        update tool_catalog_family
           set head_catalog_id = new.id,
               updated_at = new.created_at
         where id = new.family_id
           and owner_account_id = new.owner_account_id
           and head_catalog_id is null;
        if not found then
            raise exception 'Root Tool Catalog family already has a head';
        end if;
    end if;
    return new;
end;
$$;

create trigger tool_catalog_advance_root_family
after insert on tool_catalog
for each row execute function advance_root_tool_catalog_family();

create table managed_runtime_catalog_transition (
    runtime_id uuid not null,
    sequence bigint not null,
    owner_account_id uuid not null,
    source_catalog_id uuid not null,
    source_catalog_checksum char(64) not null,
    target_catalog_id uuid not null,
    target_catalog_checksum char(64) not null,
    diff_checksum char(64) not null,
    transition_kind varchar(16) not null,
    created_at timestamptz not null,
    primary key (runtime_id, sequence),
    constraint managed_runtime_transition_runtime_owner_fk
        foreign key (runtime_id, owner_account_id)
        references managed_runtime_instance(id, owner_account_id) on delete cascade,
    constraint managed_runtime_transition_source_owner_fk
        foreign key (source_catalog_id, owner_account_id)
        references tool_catalog(id, owner_account_id),
    constraint managed_runtime_transition_target_owner_fk
        foreign key (target_catalog_id, owner_account_id)
        references tool_catalog(id, owner_account_id),
    constraint managed_runtime_transition_sequence_valid check (sequence > 0),
    constraint managed_runtime_transition_distinct_valid check (source_catalog_id <> target_catalog_id),
    constraint managed_runtime_transition_checksums_valid check (
        source_catalog_checksum ~ '^[a-f0-9]{64}$'
        and target_catalog_checksum ~ '^[a-f0-9]{64}$'
        and diff_checksum ~ '^[a-f0-9]{64}$'
    ),
    constraint managed_runtime_transition_kind_valid check (
        transition_kind in ('MIGRATION', 'ROLLBACK')
    )
);

create or replace function reject_runtime_catalog_transition_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Managed Runtime Catalog transitions are append-only';
end;
$$;

create trigger managed_runtime_catalog_transition_append_only
before update or delete on managed_runtime_catalog_transition
for each row execute function reject_runtime_catalog_transition_mutation();
