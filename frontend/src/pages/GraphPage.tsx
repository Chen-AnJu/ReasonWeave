import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type OnSelectionChangeParams,
  type ReactFlowInstance,
} from '@xyflow/react';
import { toPng } from 'html-to-image';
import { AlertTriangle, Download, Expand, Focus, Info, Network, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { GraphEdge, GraphNode, GraphView } from '../api/types';
import { EventTabs } from '../components/EventTabs';
import { Button, Card, EmptyState, ErrorState, LoadingState, PageHeader, Select, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { formatPercent } from '../shared/format';
import { useDomainLabels } from '../shared/domainPresentationContext';
import type { DomainLabels } from '../shared/domainPresentationLabels';
import { jsonRecord, jsonString } from '../shared/json';
import { knowledgeUnitLabel, nodeTypeLabel, relationLabel, statusLabel } from '../shared/presentation';

type GraphCardData = {
  graphNode: GraphNode;
  dimmed: boolean;
  scopedKey: string;
};

const nodeTypes = { graphCard: GraphCardNode };
const relationTypes: GraphEdge['type'][] = [
  'RELATES_TO',
  'OBSERVED_FROM',
  'SUPPORTS',
  'CONTRADICTS',
  'EXPLAINS',
  'GROUNDED_BY',
  'MISSING_FOR',
];

export function GraphPage() {
  const { eventId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const event = useQuery(queries.event(eventId));
  const runs = useInfiniteQuery(queries.investigations(eventId));
  const runItems = runs.data?.pages.flatMap((page) => page.items) ?? [];
  const [runId, setRunId] = useState(searchParams.get('investigation_id') ?? '');
  const activeRunId = runId || runItems[0]?.id || '';
  const activeRun = runItems.find((item) => item.id === activeRunId);
  const scopedKey = activeRun
    ? `${activeRun.domain_pack_key}/${activeRun.domain_pack_version}`
    : event.data?.domain_pack_key ?? '';
  const domainLabels = useDomainLabels();
  const graph = useQuery({
    queryKey: ['event-graph', eventId, activeRunId],
    queryFn: () => reasonweaveApi.graph(eventId, activeRunId),
    enabled: Boolean(activeRunId),
  });
  const [hypothesisId, setHypothesisId] = useState('');
  const [relation, setRelation] = useState('ALL');
  const [minimumContribution, setMinimumContribution] = useState(0);
  const [selectedId, setSelectedId] = useState<string>();
  const [flow, setFlow] = useState<ReactFlowInstance<Node<GraphCardData>, Edge>>();
  const canvasRef = useRef<HTMLDivElement>(null);
  const closeInspectorRef = useRef<HTMLButtonElement>(null);
  const [drawerMode, setDrawerMode] = useState(false);
  const handleSelectionChange = useCallback(({ nodes }: OnSelectionChangeParams<Node<GraphCardData>, Edge>) => {
    const nextSelectedId = nodes[0]?.id;
    setSelectedId((current) => current === nextSelectedId ? current : nextSelectedId);
  }, []);

  const hypotheses = graph.data?.nodes.filter((node) => node.type === 'HYPOTHESIS') ?? [];
  const activeHypothesisId = hypothesisId || hypotheses[0]?.id || 'ALL';
  const nodeDisplayLabel = useCallback(
    (node: GraphNode) => displayNodeLabel(node, domainLabels, scopedKey),
    [domainLabels, scopedKey],
  );
  const filtered = useMemo(() => graph.data
    ? buildFlow(graph.data, activeHypothesisId, relation, minimumContribution, selectedId, scopedKey, nodeDisplayLabel)
    : { nodes: [], edges: [] }, [graph.data, activeHypothesisId, relation, minimumContribution, selectedId, scopedKey, nodeDisplayLabel]);
  const selected = graph.data?.nodes.find((node) => node.id === selectedId);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 1100px)');
    const update = () => setDrawerMode(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);

  useEffect(() => {
    if (!drawerMode || !selected) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusTimer = window.setTimeout(() => closeInspectorRef.current?.focus(), 0);
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSelectedId(undefined);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      window.clearTimeout(focusTimer);
      document.removeEventListener('keydown', closeOnEscape);
      previous?.focus();
    };
  }, [drawerMode, selected]);

  if (event.isPending || runs.isPending) return <LoadingState label="正在准备因果关系图" />;
  if (event.isError || runs.isError) return <ErrorState error={event.error ?? runs.error} onRetry={() => { event.refetch(); runs.refetch(); }} />;
  const exportPng = async () => {
    if (!canvasRef.current || !flow) return;
    const canvas = canvasRef.current;
    const previousViewport = flow.getViewport();
    const bounds = flow.getNodesBounds(flow.getNodes());
    const padding = 32;
    const width = Math.max(320, Math.ceil(bounds.width + padding * 2));
    const height = Math.max(240, Math.ceil(bounds.height + padding * 2));
    canvas.classList.add('is-exporting');
    try {
      await flow.setViewport({ x: padding - bounds.x, y: padding - bounds.y, zoom: 1 });
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      const dataUrl = await toPng(canvas, {
        backgroundColor: '#0B0F14',
        pixelRatio: 2,
        width,
        height,
        style: { width: `${width}px`, height: `${height}px` },
        filter: (node) => !(node instanceof HTMLElement && (
          node.dataset.exportIgnore === 'true' || node.classList.contains('react-flow__controls')
        )),
      });
      const anchor = document.createElement('a');
      anchor.download = `reasonweave-graph-${eventId}-${activeRunId}.png`;
      anchor.href = dataUrl;
      anchor.click();
    } finally {
      canvas.classList.remove('is-exporting');
      await flow.setViewport(previousViewport);
    }
  };

  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="调查解释视图"
        title="因果关系图"
        description={`${event.data.title} · 图谱严格来自所选调查运行的不可变快照。`}
        actions={<>
          <Button variant="secondary" onClick={() => flow?.fitView({ padding: 0.18, duration: 300 })}><Focus size={15} />居中</Button>
          <Button variant="secondary" onClick={() => canvasRef.current?.requestFullscreen()}><Expand size={15} />全屏</Button>
          <Button onClick={exportPng} disabled={!graph.data}><Download size={15} />导出 PNG</Button>
        </>}
      />
      <EventTabs eventId={eventId} />
      <div className="rw-graph-filterbar">
        <label><span>调查运行</span><Select value={activeRunId} onChange={(value) => { setRunId(value.target.value); setHypothesisId(''); setSelectedId(undefined); }}><option value="" disabled>选择调查运行</option>{runItems.map((item) => <option value={item.id} key={item.id}>第 {item.sequence_no} 轮 · {statusLabel(item.status)}</option>)}</Select></label>
        {runs.hasNextPage && <Button variant="ghost" onClick={() => runs.fetchNextPage()} disabled={runs.isFetchingNextPage}>{runs.isFetchingNextPage ? '正在加载' : '加载更早运行'}</Button>}
        <label><span>原因假设</span><Select value={activeHypothesisId} onChange={(value) => { setHypothesisId(value.target.value); setSelectedId(undefined); }}><option value="ALL">全部假设</option>{hypotheses.map((item) => <option value={item.id} key={item.id}>{nodeDisplayLabel(item)}</option>)}</Select></label>
        <label><span>关系类型</span><Select value={relation} onChange={(value) => setRelation(value.target.value)}><option value="ALL">全部关系</option>{relationTypes.map((value) => <option key={value} value={value}>{relationLabel(value)}</option>)}</Select></label>
        <label className="rw-graph-threshold"><span>最小贡献绝对值：{minimumContribution.toFixed(2)}</span><input type="range" min="0" max="1" step="0.05" value={minimumContribution} onChange={(value) => setMinimumContribution(Number(value.target.value))} /></label>
      </div>
      <div className="rw-callout rw-callout--knowledge"><Info size={17} /><p>紫色知识节点通过“知识依据”关系提供背景，不参与支持指数；贡献阈值不会隐藏这些关系。</p></div>
      {graph.data?.stale && <div className="rw-callout rw-callout--warning"><AlertTriangle size={17} /><p>所选调查运行已过期；图谱仍显示该运行当时的快照，不混入最新证据或观察状态。</p></div>}
      {graph.data?.warnings.map((warning) => <div key={warning} className="rw-callout rw-callout--warning"><AlertTriangle size={17} /><p>{warning}</p></div>)}

      <Card className="rw-graph-shell">
        {!activeRunId ? <EmptyState title="还没有调查运行" description="完成一次调查后即可查看因果关系图。" /> : graph.isPending ? <LoadingState label="正在构建调查快照图谱" /> : graph.isError ? <ErrorState error={graph.error} onRetry={() => graph.refetch()} /> : graph.data.nodes.length === 0 ? <EmptyState title="图谱没有节点" description="该调查运行尚未生成可解释结果。" /> : (
          <div className="rw-graph-layout">
            <div className="rw-graph-canvas" ref={canvasRef} aria-label="因果关系图画布">
              <ReactFlow
                nodes={filtered.nodes}
                edges={filtered.edges}
                nodeTypes={nodeTypes}
                fitView
                fitViewOptions={{ padding: 0.08 }}
                minZoom={0.35}
                maxZoom={1.8}
                nodesDraggable={false}
                nodesConnectable={false}
                elementsSelectable
                onInit={setFlow}
                onNodeClick={(_, node) => setSelectedId(node.id)}
                onSelectionChange={handleSelectionChange}
                onKeyDownCapture={(keyEvent) => {
                  if (keyEvent.key !== 'Enter' && keyEvent.key !== ' ') return;
                  const target = keyEvent.target instanceof HTMLElement ? keyEvent.target : null;
                  const focusedNode = target?.closest<HTMLElement>('.react-flow__node[data-id]');
                  if (!focusedNode?.dataset.id) return;
                  keyEvent.preventDefault();
                  keyEvent.stopPropagation();
                  setSelectedId(focusedNode.dataset.id);
                }}
                onPaneClick={() => setSelectedId(undefined)}
                aria-label="调查因果关系图"
              >
                <Background color="#273241" gap={24} size={1} />
                <Controls position="bottom-left" showInteractive={false} />
              </ReactFlow>
            </div>
            {drawerMode && selected && <button className="rw-graph-backdrop" aria-label="关闭节点检查器" onClick={() => setSelectedId(undefined)} />}
            <aside className={`rw-graph-inspector ${selected ? 'is-open' : ''}`} aria-label="节点检查器" role={drawerMode ? 'dialog' : undefined} aria-modal={drawerMode ? true : undefined} hidden={drawerMode && !selected}>
              <button ref={closeInspectorRef} className="rw-graph-inspector__close" onClick={() => setSelectedId(undefined)} aria-label="关闭节点检查器"><X size={17} /></button>
              {!selected ? <div className="rw-state rw-state--compact"><Network /><strong>选择一个节点</strong><p>查看中文解释、计分边界和技术原值。</p></div> : <NodeInspector node={selected} edges={graph.data.edges} scopedKey={scopedKey} />}
            </aside>
          </div>
        )}
      </Card>
    </div>
  );
}

function GraphCardNode({ data, selected }: NodeProps<Node<GraphCardData>>) {
  const value = data.graphNode;
  const labels = useDomainLabels();
  const label = displayNodeLabel(value, labels, data.scopedKey);
  return (
    <div className={`rw-flow-node rw-flow-node--${value.type.toLowerCase()} ${selected ? 'is-selected' : ''}`} style={{ opacity: data.dimmed ? 0.24 : 1 }} aria-label={`${nodeTypeLabel(value.type)}：${label}`}>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <div className="rw-flow-node__type">{nodeTypeLabel(value.type)}</div>
      <strong>{label}</strong>
      {value.subtitle && <small>{displaySubtitle(value, labels, data.scopedKey)}</small>}
      <div className="rw-flow-node__footer">
        {value.score != null && <span>{value.type === 'HYPOTHESIS' ? `支持指数 ${value.score}` : `优先级 ${value.score.toFixed(2)}`}</span>}
        {value.type === 'KNOWLEDGE' && <span>领域知识 · 不计分</span>}
        {value.status && <span>{statusLabel(value.status)}</span>}
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  );
}

function NodeInspector({ node, edges, scopedKey }: { node: GraphNode; edges: GraphEdge[]; scopedKey: string }) {
  const labels = useDomainLabels();
  const connected = edges.filter((edge) => edge.source === node.id || edge.target === node.id);
  return (
    <div className="rw-graph-inspector__body">
      <div className="rw-eyebrow">{nodeTypeLabel(node.type)}</div>
      <h2>{displayNodeLabel(node, labels, scopedKey)}</h2>
      {node.subtitle && <p>{displaySubtitle(node, labels, scopedKey)}</p>}
      <div className="rw-cluster">
        {node.status && <StatusTag status={node.status} />}
        {node.type === 'KNOWLEDGE' && <><Tag tone="warning">领域包知识</Tag><Tag tone="knowledge">不参与支持指数</Tag></>}
        {node.coverage != null && <Tag tone="info">覆盖率 {formatPercent(node.coverage)}</Tag>}
      </div>
      <div className="rw-definition-list rw-graph-relations">
        {connected.map((edge) => <div key={edge.id}><span>{relationLabel(edge.type)}</span><strong>{edge.contribution == null ? '结构关系' : `${edge.contribution >= 0 ? '+' : ''}${edge.contribution.toFixed(3)}`}</strong></div>)}
      </div>
      {node.type === 'KNOWLEDGE' && <div className="rw-callout rw-callout--knowledge"><Info size={16} /><p>该节点只提供可追溯知识背景，不会影响支持指数。</p></div>}
      <TechnicalDetails><pre>{JSON.stringify({ node, connected_edges: connected }, null, 2)}</pre></TechnicalDetails>
    </div>
  );
}

function buildFlow(
  graph: GraphView,
  hypothesisId: string,
  relation: string,
  threshold: number,
  selectedId?: string,
  scopedKey = '',
  nodeDisplayLabel: (node: GraphNode) => string = (node) => node.label,
) {
  const graphNodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  const hypothesisEdges = edgesForHypothesis(graph, hypothesisId, graphNodeById);
  const allowedEdges = hypothesisEdges.filter((edge) => {
    if (relation !== 'ALL' && edge.type !== relation) return false;
    return !edge.score_affecting || Math.abs(edge.contribution ?? 0) >= threshold;
  });
  const visibleIds = new Set<string>();
  allowedEdges.forEach((edge) => { visibleIds.add(edge.source); visibleIds.add(edge.target); });
  if (hypothesisId !== 'ALL') visibleIds.add(hypothesisId);
  const visibleNodes = graph.nodes.filter((node) => visibleIds.has(node.id));
  const activePath = selectedId ? directedPath(selectedId, allowedEdges) : null;
  const layerOrder: GraphNode['type'][] = ['EVIDENCE', 'OBSERVATION', 'KNOWLEDGE', 'GAP', 'HYPOTHESIS', 'SUBJECT', 'EVENT'];
  const grouped = new Map<string, GraphNode[]>();
  layerOrder.forEach((type) => grouped.set(type, []));
  visibleNodes.forEach((node) => grouped.get(node.type)?.push(node));
  const rowGap = 132;
  const knowledge = grouped.get('KNOWLEDGE') ?? [];
  const gaps = grouped.get('GAP') ?? [];
  const knowledgeColumns = Math.min(2, Math.max(1, knowledge.length));
  const knowledgeRows = Math.ceil(knowledge.length / knowledgeColumns);
  const gapColumns = Math.min(2, Math.max(1, gaps.length));
  const gapRows = Math.ceil(gaps.length / gapColumns);
  const middleRows = knowledgeRows + (gaps.length ? gapRows + 1 : 0);
  const totalRows = Math.max(
    grouped.get('EVIDENCE')?.length ?? 0,
    grouped.get('OBSERVATION')?.length ?? 0,
    grouped.get('HYPOTHESIS')?.length ?? 0,
    middleRows,
    2,
  );
  const middleColumns = Math.max(knowledgeColumns, gapColumns);
  const hypothesisX = 500 + middleColumns * 250 + 40;
  const finalX = hypothesisX + 270;
  const centeredY = (count: number) => Math.max(0, (totalRows - count) * rowGap / 2);
  const nodes: Node<GraphCardData>[] = [];
  const addColumn = (type: GraphNode['type'], x: number, startY = centeredY(grouped.get(type)?.length ?? 0)) => {
    (grouped.get(type) ?? []).forEach((node, index) => nodes.push({
      id: node.id,
      type: 'graphCard',
      position: { x, y: startY + index * rowGap },
      data: { graphNode: node, dimmed: Boolean(activePath && !activePath.has(node.id)), scopedKey },
      selected: node.id === selectedId,
      ariaLabel: `${nodeTypeLabel(node.type)}：${nodeDisplayLabel(node)}`,
    }));
  };
  addColumn('EVIDENCE', 0);
  addColumn('OBSERVATION', 250);
  const middleStartY = centeredY(middleRows);
  knowledge.forEach((node, index) => {
    const column = Math.floor(index / knowledgeRows);
    const row = index % knowledgeRows;
    nodes.push({
      id: node.id,
      type: 'graphCard',
      position: { x: 500 + column * 250, y: middleStartY + row * rowGap },
      data: { graphNode: node, dimmed: Boolean(activePath && !activePath.has(node.id)), scopedKey },
      selected: node.id === selectedId,
      ariaLabel: `${nodeTypeLabel(node.type)}：${nodeDisplayLabel(node)}`,
    });
  });
  const gapStartY = middleStartY + (knowledgeRows + (knowledge.length ? 1 : 0)) * rowGap;
  gaps.forEach((node, index) => {
    const column = Math.floor(index / gapRows);
    const row = index % gapRows;
    nodes.push({
      id: node.id,
      type: 'graphCard',
      position: { x: 500 + column * 250, y: gapStartY + row * rowGap },
      data: { graphNode: node, dimmed: Boolean(activePath && !activePath.has(node.id)), scopedKey },
      selected: node.id === selectedId,
      ariaLabel: `${nodeTypeLabel(node.type)}：${nodeDisplayLabel(node)}`,
    });
  });
  addColumn('HYPOTHESIS', hypothesisX);
  const finalNodes = [...(grouped.get('SUBJECT') ?? []), ...(grouped.get('EVENT') ?? [])];
  finalNodes.forEach((node, index) => nodes.push({
    id: node.id,
    type: 'graphCard',
    position: { x: finalX, y: centeredY(finalNodes.length) + index * rowGap },
    data: { graphNode: node, dimmed: Boolean(activePath && !activePath.has(node.id)), scopedKey },
    selected: node.id === selectedId,
    ariaLabel: `${nodeTypeLabel(node.type)}：${nodeDisplayLabel(node)}`,
  }));
  const edges: Edge[] = allowedEdges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.contribution == null ? relationLabel(edge.type) : `${edge.contribution >= 0 ? '+' : ''}${edge.contribution.toFixed(2)}`,
    style: {
      stroke: edgeColor(edge.type),
      strokeWidth: edge.score_affecting ? 2.2 : 1.5,
      opacity: activePath && (!activePath.has(edge.source) || !activePath.has(edge.target)) ? 0.16 : 0.92,
    },
    labelStyle: { fill: '#AAB6C5', fontSize: 9 },
    labelBgStyle: { fill: '#111821', fillOpacity: 0.9 },
    markerEnd: { type: MarkerType.ArrowClosed, color: edgeColor(edge.type), width: 15, height: 15 },
    animated: edge.type === 'GROUNDED_BY',
    data: edge,
  }));
  return { nodes, edges };
}

function edgesForHypothesis(
  graph: GraphView,
  hypothesisId: string,
  nodeById: Map<string, GraphNode>,
) {
  if (hypothesisId === 'ALL') return graph.edges;
  const visible = new Set<string>([hypothesisId]);
  const upstream = [hypothesisId];
  while (upstream.length) {
    const current = upstream.shift()!;
    for (const edge of graph.edges) {
      if (edge.target !== current || visible.has(edge.source)) continue;
      const source = nodeById.get(edge.source);
      if (!source || source.type === 'EVENT' || source.type === 'SUBJECT') continue;
      if (source.type === 'HYPOTHESIS' && source.id !== hypothesisId) continue;
      visible.add(edge.source);
      upstream.push(edge.source);
    }
  }
  for (const edge of graph.edges) {
    if (edge.source !== hypothesisId) continue;
    if (nodeById.get(edge.target)?.type === 'EVENT') visible.add(edge.target);
  }
  return graph.edges.filter((edge) => visible.has(edge.source) && visible.has(edge.target));
}

function directedPath(start: string, edges: GraphEdge[]) {
  const result = new Set([start]);
  const walk = (direction: 'upstream' | 'downstream') => {
    const queue = [start];
    while (queue.length) {
      const current = queue.shift()!;
      for (const edge of edges) {
        const next = direction === 'upstream'
          ? edge.target === current ? edge.source : undefined
          : edge.source === current ? edge.target : undefined;
        if (next && !result.has(next)) {
          result.add(next);
          queue.push(next);
        }
      }
    }
  };
  walk('upstream');
  walk('downstream');
  return result;
}

function displayNodeLabel(node: GraphNode, labels: DomainLabels, scopedKey: string) {
  const metadata = jsonRecord(node.metadata);
  if (node.type === 'OBSERVATION') return labels.predicateLabel(jsonString(metadata.predicate), scopedKey, node.label);
  if (node.type === 'KNOWLEDGE') return knowledgeUnitLabel(undefined, node.label);
  if (node.type === 'EVIDENCE') {
    const label = node.label.toLowerCase();
    if (label.startsWith('image')) return '图片证据快照';
    if (label.startsWith('document')) return '文档证据快照';
    return '证据快照';
  }
  if (node.type === 'HYPOTHESIS') {
    const code = jsonString(metadata.code);
    return labels.hypothesisLabel(code, node.label, scopedKey);
  }
  return node.label;
}

function displaySubtitle(node: GraphNode, labels: DomainLabels, scopedKey: string) {
  const metadata = jsonRecord(node.metadata);
  if (node.type === 'HYPOTHESIS') return '受控领域假设';
  if (node.type === 'OBSERVATION') return '调查时观察快照';
  if (node.type === 'EVIDENCE') return '调查运行中的证据快照';
  if (node.type === 'KNOWLEDGE') return chineseText(node.subtitle, '领域包知识文档');
  if (node.type === 'GAP') return '待补充的区分性证据';
  if (node.type === 'SUBJECT') return labels.subjectLabel(jsonString(metadata.type), undefined, scopedKey);
  if (node.type === 'EVENT') return labels.eventTypeLabel(jsonString(metadata.event_type), scopedKey);
  return node.subtitle;
}

function chineseText(value: string | undefined, fallback: string) {
  return value && /[\u3400-\u9fff]/u.test(value) ? value : fallback;
}

function edgeColor(type: GraphEdge['type']) {
  if (type === 'SUPPORTS') return '#4DD4A4';
  if (type === 'CONTRADICTS') return '#FF6B7A';
  if (type === 'GROUNDED_BY') return '#D6A7FF';
  if (type === 'MISSING_FOR') return '#F3B65A';
  return '#65A8FF';
}
