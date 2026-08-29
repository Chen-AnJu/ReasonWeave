alter table evidence
    add column if not exists generation integer not null default 1;

alter table observations
    add column if not exists generation integer not null default 1;

create index if not exists idx_observations_evidence_generation
    on observations(evidence_id, generation desc, created_at desc, id desc);

alter table investigation_runs
    add column if not exists evidence_snapshot_schema_version integer not null default 1,
    add column if not exists evidence_snapshot jsonb;

alter table evidence_gaps
    add column if not exists recommendation_id varchar(160),
    add column if not exists expected_predicate varchar(160);

alter table idempotency_records
    add column if not exists state varchar(24) not null default 'COMPLETED',
    add column if not exists resource_id varchar(32);

alter table idempotency_records
    alter column response_status drop not null,
    alter column response_body drop not null;

create index if not exists idx_idempotency_expiry
    on idempotency_records(expires_at);

create index if not exists idx_investigation_running_started
    on investigation_runs(status, started_at)
    where status = 'RUNNING';
