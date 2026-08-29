drop index if exists idx_knowledge_units_vector;

update knowledge_units set embedding = null where embedding is not null;

alter table knowledge_units
    alter column embedding type vector(1024)
    using null::vector(1024);

create index idx_knowledge_units_vector
    on knowledge_units using hnsw (embedding vector_cosine_ops);

alter table knowledge_sources
    add column if not exists embedding_provider varchar(80),
    add column if not exists embedding_model varchar(200),
    add column if not exists embedding_dimension integer,
    add column if not exists embedding_model_digest varchar(200),
    add column if not exists embedding_query_instruction text,
    add column if not exists index_profile_fingerprint char(64);

alter table retrieval_runs
    add column if not exists embedding_provider varchar(80),
    add column if not exists embedding_model_digest varchar(200),
    add column if not exists index_profile_fingerprint char(64);

alter table investigation_runs
    add column if not exists domain_pack_key varchar(80),
    add column if not exists domain_pack_version varchar(40),
    add column if not exists domain_pack_fingerprint char(64);

create index if not exists idx_evidence_bundle_external_id
    on evidence(workspace_id, event_id, ((metadata ->> 'external_id')))
    where metadata ? 'external_id';
