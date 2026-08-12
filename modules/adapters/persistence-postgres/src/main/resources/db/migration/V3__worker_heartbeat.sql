create table worker_heartbeat (
    worker_id varchar(64) primary key,
    observed_at timestamptz not null,
    constraint worker_heartbeat_id_valid check (worker_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$')
);

create index worker_heartbeat_observed_idx on worker_heartbeat(observed_at desc);
