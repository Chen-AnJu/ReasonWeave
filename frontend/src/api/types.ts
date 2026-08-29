import type { components } from './schema';

type Schemas = components['schemas'];

export type EventIr = {
  schema_version: 'eventir/0.1';
  event: {
    id?: string;
    type: string;
    title: string;
    description?: string;
    reference_code?: string;
    domain_pack?: string;
    occurred_at?: { start?: string; end?: string };
    location?: { name?: string; latitude?: number; longitude?: number };
  };
  subjects: Array<{ id?: string; type: string; label: string; attributes?: Record<string, unknown> }>;
  claims?: unknown[];
  evidence?: unknown[];
  observations?: unknown[];
  hypotheses?: unknown[];
  contradictions?: unknown[];
  unknowns?: unknown[];
};

export type EventSummary = Schemas['EventSummary'];
export type EventPage = Schemas['EventPage'];
export type EventDetail = Omit<Schemas['EventDetail'], 'event_ir'> & { event_ir: EventIr };
export type EventView = Omit<Schemas['EventView'], 'event'> & { event: EventDetail };
export type Observation = Schemas['ObservationView'];
export type EvidenceSummary = Schemas['EvidenceSummary'];
export type EvidencePage = Schemas['EvidencePage'];
export type EvidenceDetail = Schemas['EvidenceDetail'];
export type ObservationBundle = Schemas['ObservationBundleRequest'];
export type ObservationBundleImport = Schemas['ObservationBundleImportView'];
export type KnowledgeSource = Schemas['SourceView'];
export type KnowledgeDocument = Schemas['DocumentView'];
export type RetrievalHit = Schemas['RetrievalHitView'];
export type RetrievalRun = Schemas['RetrievalRunView'];
export type RetrievalRequest = Schemas['RetrievalRequest'];
export type HypothesisResult = Schemas['HypothesisResultView'];
export type InvestigationResult = Schemas['InvestigationResultView'];
export type InvestigationRun = Omit<Schemas['InvestigationRunView'], 'event_ir_snapshot'> & {
  event_ir_snapshot: EventIr;
};
export type InvestigationPage = Omit<Schemas['InvestigationPage'], 'items'> & {
  items: InvestigationRun[];
};
export type KnowledgeContext = Schemas['KnowledgeContextView'];
export type NextEvidence = Schemas['NextEvidenceView'];
export type RunDiff = Schemas['RunDiffView'];

export type GraphNodeType = 'EVENT' | 'SUBJECT' | 'EVIDENCE' | 'OBSERVATION' | 'HYPOTHESIS' | 'KNOWLEDGE' | 'GAP';
export type GraphEdgeType = 'RELATES_TO' | 'OBSERVED_FROM' | 'SUPPORTS' | 'CONTRADICTS' | 'EXPLAINS' | 'GROUNDED_BY' | 'MISSING_FOR';
export type GraphNode = Omit<Schemas['GraphNode'], 'type'> & { type: GraphNodeType };
export type GraphEdge = Omit<Schemas['GraphEdge'], 'type'> & { type: GraphEdgeType };
export type GraphView = Omit<Schemas['GraphView'], 'nodes' | 'edges'> & {
  nodes: GraphNode[];
  edges: GraphEdge[];
};

export type AuditEntry = Schemas['AuditEntry'];
export type AuditPage = Schemas['AuditPage'];
export type KnowledgeSourceDetail = Schemas['SourceDetailView'];
export type KnowledgeUnitSummary = Schemas['UnitSummaryView'];
export type KnowledgeUnitPage = Schemas['UnitPageView'];
export type CitationUsage = Schemas['CitationUsageView'];
export type CitationUsagePage = Schemas['CitationUsagePageView'];
export type RetrievalUsage = Schemas['RetrievalUsageView'];
export type RetrievalUsagePage = Schemas['RetrievalUsagePageView'];
export type KnowledgeUnitDetail = Schemas['UnitDetailView'];
export type DomainPackSummary = Schemas['DomainPackSummary'];
export type DomainPackDetail = Schemas['DomainPackDetail'];
export type RuntimeView = Schemas['RuntimeView'];
export type EventTypeView = Schemas['EventTypeView'];
export type EvidenceInputView = Schemas['EvidenceInputView'];
