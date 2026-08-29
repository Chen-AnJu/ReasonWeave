create index if not exists idx_knowledge_units_source_status_title
    on knowledge_units(knowledge_source_id, status, title, id);

create index if not exists idx_knowledge_citations_unit_created
    on knowledge_citations(knowledge_unit_id, created_at desc);

create index if not exists idx_retrieval_hits_unit_run
    on retrieval_hits(knowledge_unit_id, retrieval_run_id);

create index if not exists idx_audit_event_stable_page
    on audit_events(event_id, occurred_at desc, id desc);

create index if not exists idx_audit_event_action
    on audit_events(event_id, action, occurred_at desc, id desc);
