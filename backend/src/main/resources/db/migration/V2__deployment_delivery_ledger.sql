create table deployment_delivery_ledger (
    id uuid primary key,
    repository_name varchar(255) not null,
    target_environment varchar(32) not null check (target_environment in ('test-api', 'test-web')),
    deployment_sha varchar(40) not null,
    pull_request_number integer not null check (pull_request_number > 0),
    status varchar(24) not null check (status in ('CLAIMED', 'REQUESTING', 'REQUESTED', 'SUCCEEDED', 'SAFE_FAILURE', 'UNKNOWN')),
    attempt_count integer not null check (attempt_count > 0),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    request_started_at timestamp with time zone,
    completed_at timestamp with time zone,
    version bigint not null default 0,
    constraint deployment_delivery_ledger_key_uq unique (repository_name, target_environment, deployment_sha)
);

create index deployment_delivery_ledger_status_idx
    on deployment_delivery_ledger(status, updated_at);
