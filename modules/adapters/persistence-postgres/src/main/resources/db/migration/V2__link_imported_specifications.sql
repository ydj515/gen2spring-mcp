alter table generation_job
    drop constraint generation_job_specification_valid;

alter table generation_job
    add constraint generation_job_specification_valid check (
        (kind = 'GENERATION' and specification_id is not null)
        or (kind = 'SPEC_IMPORT' and (
            (status = 'SUCCEEDED' and specification_id is not null)
            or (status <> 'SUCCEEDED' and specification_id is null)
        ))
    );
