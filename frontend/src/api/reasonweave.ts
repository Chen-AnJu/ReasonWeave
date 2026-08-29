import { apiRequest } from './client';
import type {
  AuditPage,
  CitationUsagePage,
  DomainPackDetail,
  DomainPackSummary,
  EventDetail,
  EventIr,
  EventPage,
  EventView,
  EvidenceDetail,
  EvidencePage,
  EventTypeView,
  ObservationBundle,
  ObservationBundleImport,
  GraphView,
  InvestigationRun,
  InvestigationPage,
  KnowledgeContext,
  KnowledgeDocument,
  KnowledgeSource,
  KnowledgeSourceDetail,
  KnowledgeUnitDetail,
  KnowledgeUnitPage,
  NextEvidence,
  Observation,
  RetrievalRun,
  RetrievalRequest,
  RetrievalUsagePage,
  RunDiff,
  RuntimeView,
} from './types';

export const reasonweaveApi = {
  runtime: () => apiRequest<RuntimeView>('/api/v1/runtime'),

  events: (query = '', status = '', cursor?: string, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (query) params.set('query', query);
    if (status) params.set('status', status);
    if (cursor) params.set('cursor', cursor);
    return apiRequest<EventPage>(`/api/v1/events?${params}`);
  },
  event: (id: string) => apiRequest<EventDetail>(`/api/v1/events/${id}`),
  eventView: (id: string) => apiRequest<EventView>(`/api/v1/events/${id}/view`),
  createEvent: (eventIr: EventIr) => apiRequest<EventDetail>('/api/v1/events', {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ event_ir: eventIr }),
  }),

  evidence: (eventId?: string, cursor?: string, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (eventId) params.set('event_id', eventId);
    if (cursor) params.set('cursor', cursor);
    return apiRequest<EvidencePage>(`/api/v1/evidence?${params}`);
  },
  evidenceDetail: (id: string) => apiRequest<EvidenceDetail>(`/api/v1/evidence/${id}`),
  addTextEvidence: (eventId: string, text: string) =>
    apiRequest<EvidenceDetail>(`/api/v1/events/${eventId}/evidence/text`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),
  uploadEvidence: (eventId: string, file: File) => {
    const body = new FormData();
    body.append('file', file);
    return apiRequest<EvidenceDetail>(`/api/v1/events/${eventId}/evidence`, { method: 'POST', body });
  },
  importObservationBundle: (eventId: string, bundle: ObservationBundle) =>
    apiRequest<ObservationBundleImport>(`/api/v1/events/${eventId}/evidence/bundles`, {
      method: 'POST',
      body: JSON.stringify(bundle),
    }),
  verifyObservation: (id: string, version: number, verificationStatus: string, description?: string) =>
    apiRequest<Observation>(`/api/v1/observations/${id}`, {
      method: 'PATCH',
      headers: { 'If-Match': String(version) },
      body: JSON.stringify({ verification_status: verificationStatus, description }),
    }),
  reprocessEvidence: (id: string) => apiRequest<EvidenceDetail>(`/api/v1/evidence/${id}/reprocess`, {
    method: 'POST',
  }),
  knowledgeSources: () => apiRequest<KnowledgeSource[]>('/api/v1/knowledge/sources'),
  knowledgeSource: (id: string) => apiRequest<KnowledgeSourceDetail>(`/api/v1/knowledge/sources/${id}`),
  knowledgeUnits: (sourceId: string, cursor?: string, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<KnowledgeUnitPage>(`/api/v1/knowledge/sources/${sourceId}/units?${params}`);
  },
  knowledgeUnit: (id: string) => apiRequest<KnowledgeUnitDetail>(`/api/v1/knowledge/units/${id}`),
  knowledgeCitationUsages: (id: string, cursor?: string, limit = 20) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<CitationUsagePage>(`/api/v1/knowledge/units/${id}/citation-usages?${params}`);
  },
  knowledgeRetrievalUsages: (id: string, cursor?: string, limit = 20) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<RetrievalUsagePage>(`/api/v1/knowledge/units/${id}/retrieval-usages?${params}`);
  },
  knowledgeDocuments: (sourceId?: string) => apiRequest<KnowledgeDocument[]>(
    `/api/v1/knowledge/documents${sourceId ? `?source_id=${encodeURIComponent(sourceId)}` : ''}`,
  ),
  debugRetrieval: (
    query: string,
    eventType: string,
    domainPackKey: string,
    intent = 'CAUSE_CANDIDATES',
    observedPredicates: string[] = [],
  ) => {
    const request: RetrievalRequest = {
      query,
      event_type: eventType,
      observed_predicates: observedPredicates,
      intent,
      domain_pack_key: domainPackKey,
    };
    return apiRequest<RetrievalRun>('/api/v1/retrieval/debug', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  },

  investigations: (eventId: string, cursor?: string, limit = 20) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<InvestigationPage>(`/api/v1/events/${eventId}/investigations?${params}`);
  },
  startInvestigation: (eventId: string) => apiRequest<InvestigationRun>(
    `/api/v1/events/${eventId}/investigations`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    },
  ),
  investigation: (id: string) => apiRequest<InvestigationRun>(`/api/v1/investigations/${id}`),
  knowledgeContext: (id: string) => apiRequest<KnowledgeContext>(
    `/api/v1/investigations/${id}/knowledge-context`,
  ),
  nextEvidence: (id: string) => apiRequest<NextEvidence[]>(
    `/api/v1/investigations/${id}/next-evidence`,
  ),
  runDiff: (id: string, against?: string) => apiRequest<RunDiff>(
    `/api/v1/investigations/${id}/diff${against ? `?against=${encodeURIComponent(against)}` : ''}`,
  ),
  graph: (eventId: string, investigationId: string) => apiRequest<GraphView>(
    `/api/v1/events/${eventId}/graph?investigation_id=${encodeURIComponent(investigationId)}`,
  ),
  audit: (
    eventId: string,
    filters: { cursor?: string; actorId?: string; action?: string; runId?: string; limit?: number } = {},
  ) => {
    const params = new URLSearchParams({ limit: String(filters.limit ?? 50) });
    if (filters.cursor) params.set('cursor', filters.cursor);
    if (filters.actorId) params.set('actor_id', filters.actorId);
    if (filters.action) params.set('action', filters.action);
    if (filters.runId) params.set('run_id', filters.runId);
    return apiRequest<AuditPage>(`/api/v1/events/${eventId}/audit?${params}`);
  },
  auditExportUrl: (eventId: string, filters: { actorId?: string; action?: string; runId?: string } = {}) => {
    const params = new URLSearchParams();
    if (filters.actorId) params.set('actor_id', filters.actorId);
    if (filters.action) params.set('action', filters.action);
    if (filters.runId) params.set('run_id', filters.runId);
    const query = params.toString();
    return `/api/v1/events/${eventId}/audit/export${query ? `?${query}` : ''}`;
  },
  domainPacks: () => apiRequest<DomainPackSummary[]>('/api/v1/domain-packs'),
  domainPack: (key: string, version: string) => apiRequest<DomainPackDetail>(
    `/api/v1/domain-packs/${encodeURIComponent(key)}/versions/${encodeURIComponent(version)}`,
  ),
  domainPackEventType: (key: string, version: string, eventType: string) => apiRequest<EventTypeView>(
    `/api/v1/domain-packs/${encodeURIComponent(key)}/versions/${encodeURIComponent(version)}/event-types/${encodeURIComponent(eventType)}`,
  ),
  openApi: async () => {
    const response = await fetch('/api/v1/openapi', { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`无法读取 OpenAPI（HTTP ${response.status}）`);
    return response.json() as Promise<unknown>;
  },
};
