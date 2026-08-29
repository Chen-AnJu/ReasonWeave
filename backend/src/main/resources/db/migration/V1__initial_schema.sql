create extension if not exists vector;

create table workspaces (
    id varchar(32) primary key,
    name varchar(160) not null,
    created_at timestamptz not null default now()
);

create table idempotency_records (
    workspace_id varchar(32) not null,
    endpoint varchar(240) not null,
    idempotency_key varchar(200) not null,
    request_hash char(64) not null,
    response_status integer not null,
    response_body jsonb not null,
    expires_at timestamptz not null,
    primary key (workspace_id, endpoint, idempotency_key)
);

create table events (
    id varchar(32) primary key,
    workspace_id varchar(32) not null references workspaces(id),
    reference_code varchar(32) not null,
    event_type varchar(80) not null,
    title varchar(200) not null,
    description text,
    occurred_start timestamptz,
    occurred_end timestamptz,
    location_name varchar(300),
    latitude numeric(9,6),
    longitude numeric(9,6),
    status varchar(32) not null,
    domain_pack_key varchar(120),
    event_ir jsonb not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(workspace_id, reference_code)
);
create index idx_events_workspace_updated on events(workspace_id, updated_at desc);
create index idx_events_workspace_status on events(workspace_id, status);
create index idx_events_event_ir on events using gin(event_ir);

create table evidence (
    id varchar(32) primary key,
    event_id varchar(32) not null references events(id),
    workspace_id varchar(32) not null references workspaces(id),
    type varchar(32) not null,
    source varchar(64) not null,
    status varchar(32) not null,
    blob_key varchar(500),
    original_name varchar(500),
    content_text text,
    content_type varchar(120),
    checksum_sha256 char(64),
    captured_at timestamptz,
    latitude numeric(9,6),
    longitude numeric(9,6),
    reliability numeric(4,3) not null default 0.800,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique(workspace_id, checksum_sha256, event_id)
);
create index idx_evidence_event on evidence(event_id, created_at desc);

create table observations (
    id varchar(32) primary key,
    evidence_id varchar(32) not null references evidence(id),
    workspace_id varchar(32) not null references workspaces(id),
    subject_id varchar(32),
    predicate varchar(160) not null,
    value jsonb not null,
    description text,
    model_confidence numeric(4,3) not null,
    verification_status varchar(32) not null,
    provenance jsonb not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index idx_observations_evidence on observations(evidence_id);

create table knowledge_sources (
    id varchar(32) primary key,
    workspace_id varchar(32) not null references workspaces(id),
    domain_pack_key varchar(120) not null,
    name varchar(240) not null,
    source_type varchar(40) not null,
    version varchar(80) not null,
    license varchar(240),
    status varchar(32) not null,
    fixture_only boolean not null default false,
    production_allowed boolean not null default true,
    published_at timestamptz,
    created_at timestamptz not null default now(),
    unique(workspace_id, domain_pack_key, name, version)
);

create table knowledge_documents (
    id varchar(32) primary key,
    knowledge_source_id varchar(32) not null references knowledge_sources(id),
    workspace_id varchar(32) not null references workspaces(id),
    external_id varchar(120),
    title varchar(300) not null,
    blob_key varchar(500),
    content_type varchar(120) not null,
    checksum_sha256 char(64) not null,
    language varchar(20) not null default 'zh-CN',
    parse_status varchar(32) not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table knowledge_units (
    id varchar(32) primary key,
    workspace_id varchar(32) not null references workspaces(id),
    knowledge_source_id varchar(32) not null references knowledge_sources(id),
    document_id varchar(32) not null references knowledge_documents(id),
    domain_pack_key varchar(120) not null,
    topic varchar(160),
    title varchar(300) not null,
    content text not null,
    search_text text not null default '',
    content_tsv tsvector generated always as (
        to_tsvector('simple'::regconfig, coalesce(title, '') || ' ' || coalesce(search_text, '') || ' ' || content)
    ) stored,
    embedding vector(1536),
    applicability jsonb not null default '{}'::jsonb,
    expected_predicates jsonb not null default '[]'::jsonb,
    source_locator jsonb not null,
    source_version varchar(80) not null,
    content_hash char(64) not null,
    status varchar(32) not null,
    created_at timestamptz not null default now()
);
create index idx_knowledge_units_fts on knowledge_units using gin(content_tsv);
create index idx_knowledge_units_vector on knowledge_units using hnsw (embedding vector_cosine_ops);
create index idx_knowledge_units_scope on knowledge_units(workspace_id, domain_pack_key, status);

create table investigation_runs (
    id varchar(32) primary key,
    event_id varchar(32) not null references events(id),
    workspace_id varchar(32) not null references workspaces(id),
    sequence_no integer not null,
    status varchar(32) not null,
    event_version bigint not null,
    evidence_snapshot_hash char(64) not null,
    model_policy_version varchar(80) not null,
    rule_pack_version varchar(80) not null,
    knowledge_index_version varchar(120) not null,
    retrieval_run_id varchar(32),
    event_ir_snapshot jsonb not null,
    result_snapshot jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    error_code varchar(80),
    error_message text,
    created_at timestamptz not null default now(),
    unique(event_id, sequence_no)
);

create table retrieval_runs (
    id varchar(32) primary key,
    investigation_run_id varchar(32) references investigation_runs(id),
    workspace_id varchar(32) not null references workspaces(id),
    query_plan jsonb not null,
    retrieval_config jsonb not null,
    index_version varchar(120) not null,
    embedding_model varchar(160) not null,
    created_at timestamptz not null default now()
);

alter table investigation_runs
    add constraint fk_investigation_retrieval
    foreign key (retrieval_run_id) references retrieval_runs(id);

create table retrieval_hits (
    retrieval_run_id varchar(32) not null references retrieval_runs(id),
    knowledge_unit_id varchar(32) not null references knowledge_units(id),
    query_intent varchar(80) not null,
    keyword_rank integer,
    keyword_score numeric(12,8),
    vector_rank integer,
    vector_score numeric(12,8),
    fusion_rank integer not null,
    fusion_score numeric(12,8) not null,
    applicability_score numeric(12,8) not null,
    selected boolean not null default false,
    selection_reason text,
    primary key(retrieval_run_id, knowledge_unit_id, query_intent)
);

create table hypotheses (
    id varchar(32) primary key,
    investigation_run_id varchar(32) not null references investigation_runs(id),
    code varchar(160) not null,
    title varchar(240) not null,
    description text,
    status varchar(32) not null,
    score integer not null,
    score_band varchar(32) not null,
    evidence_coverage numeric(5,4) not null,
    generated_by varchar(80) not null,
    expected_evidence jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

create table hypothesis_evidence (
    hypothesis_id varchar(32) not null references hypotheses(id),
    evidence_id varchar(32) references evidence(id),
    observation_id varchar(32) references observations(id),
    relation varchar(40) not null,
    rule_weight numeric(8,5) not null,
    source_reliability numeric(8,5) not null,
    extraction_confidence numeric(8,5) not null,
    relevance numeric(8,5) not null,
    contribution numeric(10,6) not null,
    reason text not null,
    rule_id varchar(160) not null,
    rule_version varchar(40) not null,
    primary key(hypothesis_id, observation_id, rule_id)
);

create table knowledge_citations (
    id varchar(32) primary key,
    investigation_run_id varchar(32) not null references investigation_runs(id),
    knowledge_unit_id varchar(32) not null references knowledge_units(id),
    target_type varchar(40) not null,
    target_id varchar(32) not null,
    source_locator jsonb not null,
    source_version varchar(80) not null,
    content_hash char(64) not null,
    usage_reason text not null,
    created_at timestamptz not null default now()
);

create table evidence_gaps (
    id varchar(32) primary key,
    investigation_run_id varchar(32) not null references investigation_runs(id),
    evidence_type varchar(40) not null,
    title varchar(240) not null,
    reason text not null,
    discriminates jsonb not null,
    estimated_impact varchar(20) not null,
    acquisition_cost varchar(20) not null,
    priority_score numeric(10,6) not null,
    status varchar(32) not null,
    source varchar(40) not null,
    created_at timestamptz not null default now()
);

create table audit_events (
    id varchar(32) primary key,
    workspace_id varchar(32) not null references workspaces(id),
    event_id varchar(32) references events(id),
    actor jsonb not null,
    action varchar(120) not null,
    resource jsonb not null,
    before_state jsonb,
    after_state jsonb,
    request_id varchar(64),
    occurred_at timestamptz not null default now()
);
create index idx_audit_event on audit_events(event_id, occurred_at desc);
