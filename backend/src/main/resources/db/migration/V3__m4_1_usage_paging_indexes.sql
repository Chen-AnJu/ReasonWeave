create index if not exists idx_knowledge_citations_unit_created_id
    on knowledge_citations(knowledge_unit_id, created_at desc, id desc);

create index if not exists idx_retrieval_runs_workspace_created_id
    on retrieval_runs(workspace_id, created_at desc, id desc);
