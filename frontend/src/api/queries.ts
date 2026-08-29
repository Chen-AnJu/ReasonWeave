import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';
import { reasonweaveApi } from './reasonweave';

export const queries = {
  runtime: () => queryOptions({ queryKey: ['runtime'], queryFn: reasonweaveApi.runtime, staleTime: Infinity }),
  events: (query = '', status = '') => queryOptions({
    queryKey: ['events', { query, status }],
    queryFn: () => reasonweaveApi.events(query, status),
  }),
  eventPages: (query = '', status = '') => infiniteQueryOptions({
    queryKey: ['event-pages', { query, status }],
    queryFn: ({ pageParam }) => reasonweaveApi.events(query, status, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.next_cursor,
  }),
  event: (id: string) => queryOptions({ queryKey: ['event', id], queryFn: () => reasonweaveApi.event(id) }),
  eventView: (id: string) => queryOptions({
    queryKey: ['event-view', id],
    queryFn: () => reasonweaveApi.eventView(id),
  }),
  evidence: (eventId?: string) => queryOptions({
    queryKey: ['evidence', eventId ?? 'all'],
    queryFn: () => reasonweaveApi.evidence(eventId),
  }),
  evidencePages: (eventId?: string) => infiniteQueryOptions({
    queryKey: ['evidence-pages', eventId ?? 'all'],
    queryFn: ({ pageParam }) => reasonweaveApi.evidence(eventId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.next_cursor,
  }),
  evidenceDetail: (id: string) => queryOptions({
    queryKey: ['evidence-detail', id],
    queryFn: () => reasonweaveApi.evidenceDetail(id),
  }),
  sources: () => queryOptions({ queryKey: ['knowledge-sources'], queryFn: reasonweaveApi.knowledgeSources }),
  source: (id: string) => queryOptions({
    queryKey: ['knowledge-source', id],
    queryFn: () => reasonweaveApi.knowledgeSource(id),
  }),
  units: (sourceId: string) => queryOptions({
    queryKey: ['knowledge-units', sourceId],
    queryFn: () => reasonweaveApi.knowledgeUnits(sourceId),
  }),
  unit: (id: string) => queryOptions({
    queryKey: ['knowledge-unit', id],
    queryFn: () => reasonweaveApi.knowledgeUnit(id),
  }),
  documents: () => queryOptions({ queryKey: ['knowledge-documents'], queryFn: () => reasonweaveApi.knowledgeDocuments() }),
  investigations: (eventId: string) => infiniteQueryOptions({
    queryKey: ['investigations', eventId],
    queryFn: ({ pageParam }) => reasonweaveApi.investigations(eventId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.next_cursor,
  }),
  domainPacks: () => queryOptions({ queryKey: ['domain-packs'], queryFn: reasonweaveApi.domainPacks }),
  domainPack: (key: string, version: string) => queryOptions({
    queryKey: ['domain-pack', key, version],
    queryFn: () => reasonweaveApi.domainPack(key, version),
  }),
  domainPackEventType: (key: string, version: string, eventType: string) => queryOptions({
    queryKey: ['domain-pack-event-type', key, version, eventType],
    queryFn: () => reasonweaveApi.domainPackEventType(key, version, eventType),
  }),
  openApi: () => queryOptions({ queryKey: ['openapi'], queryFn: reasonweaveApi.openApi, staleTime: 60_000 }),
};
