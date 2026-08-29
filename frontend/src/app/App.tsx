import { Navigate, Route, Routes } from 'react-router-dom';
import { CreateEventPage } from '../pages/CreateEventPage';
import { ApiPlaygroundPage } from '../pages/ApiPlaygroundPage';
import { AuditPage } from '../pages/AuditPage';
import { DomainPackDetailPage, DomainPacksPage } from '../pages/DomainPacksPage';
import { EventOverviewPage } from '../pages/EventOverviewPage';
import { EventsPage } from '../pages/EventsPage';
import { EvidenceDetailPage } from '../pages/EvidenceDetailPage';
import { EvidenceLibraryPage } from '../pages/EvidenceLibraryPage';
import { InvestigationPage } from '../pages/InvestigationPage';
import { GraphPage } from '../pages/GraphPage';
import { KnowledgeSourceDetailPage, KnowledgeUnitDetailPage } from '../pages/KnowledgeDetailPages';
import { KnowledgePage } from '../pages/KnowledgePage';
import { OverviewPage } from '../pages/OverviewPage';
import { RetrievalInspectorPage } from '../pages/RetrievalInspectorPage';
import { AppShell } from './AppShell';

function NotFound() {
  return <main className="rw-full-state"><div className="rw-state"><strong>页面不存在</strong><p>此路由尚未开放，或地址有误。</p><a className="rw-button rw-button--secondary" href="/">返回工作台</a></div></main>;
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<AppShell />}>
        <Route index element={<Navigate to="overview" replace />} />
        <Route path="overview" element={<OverviewPage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="events/new" element={<CreateEventPage />} />
        <Route path="events/:eventId" element={<EventOverviewPage />} />
        <Route path="events/:eventId/investigation" element={<InvestigationPage mode="workbench" />} />
        <Route path="events/:eventId/hypotheses" element={<InvestigationPage mode="compare" />} />
        <Route path="events/:eventId/next-evidence" element={<InvestigationPage mode="next" />} />
        <Route path="events/:eventId/graph" element={<GraphPage />} />
        <Route path="events/:eventId/audit" element={<AuditPage />} />
        <Route path="evidence" element={<EvidenceLibraryPage />} />
        <Route path="evidence/:evidenceId" element={<EvidenceDetailPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="knowledge/sources/:sourceId" element={<KnowledgeSourceDetailPage />} />
        <Route path="knowledge/units/:unitId" element={<KnowledgeUnitDetailPage />} />
        <Route path="retrieval" element={<RetrievalInspectorPage />} />
        <Route path="domain-packs" element={<DomainPacksPage />} />
        <Route path="domain-packs/:key/versions/:version" element={<DomainPackDetailPage />} />
        <Route path="developer/api-playground" element={<ApiPlaygroundPage />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
