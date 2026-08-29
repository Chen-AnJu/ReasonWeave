do $$
begin
    if exists (
        select 1
        from knowledge_documents
        where external_id is not null
        group by knowledge_source_id, external_id
        having count(*) > 1
    ) then
        raise exception 'Duplicate knowledge document external_id values must be resolved before migration';
    end if;
end $$;

create unique index if not exists uq_knowledge_documents_source_external_id
    on knowledge_documents(knowledge_source_id, external_id)
    where external_id is not null;

alter table knowledge_sources
    add column if not exists pack_fingerprint char(64);

alter table retrieval_hits
    add column if not exists applicability_reason varchar(64) not null default 'UNSPECIFIED';
