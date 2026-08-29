import { createHash } from 'node:crypto';

const PACK = 'kubernetes-pod-diagnostics/1.0.0';
const EVENT_TYPE = 'kubernetes_pod_failure';

function locator(kind, source, extra = {}) {
  return { kind, api_version: source.apiVersion ?? 'v1', ...extra };
}

function observation(predicate, value, description, sourceLocator, confidence = 1) {
  return { predicate, value, confidence, description, source_locator: sourceLocator };
}

function allStatuses(pod) {
  return [
    ...(pod.status?.initContainerStatuses ?? []),
    ...(pod.status?.containerStatuses ?? []),
  ];
}

function statusObservations(pod) {
  const values = [];
  const podLocator = locator('Pod', pod, {
    namespace: pod.metadata?.namespace,
    name: pod.metadata?.name,
    uid: pod.metadata?.uid,
    resource_version: pod.metadata?.resourceVersion,
  });
  for (const condition of pod.status?.conditions ?? []) {
    if (condition.type === 'PodScheduled' && condition.status === 'False') {
      if (condition.reason === 'SchedulingGated') {
        values.push(observation(
          'pod_scheduling_gated', true, 'Pod 正在等待调度门解除。',
          { ...podLocator, field: 'status.conditions[PodScheduled]', reason: condition.reason }, 1
        ));
      } else if (condition.reason === 'Unschedulable') {
        values.push(observation(
          'pod_unschedulable', true, 'Pod 当前无法被调度。',
          { ...podLocator, field: 'status.conditions[PodScheduled]', reason: condition.reason }, 1
        ));
      }
    }
  }
  if (pod.status?.reason === 'Evicted') {
    values.push(observation(
      'pod_evicted', true, 'Pod 已被驱逐。',
      { ...podLocator, field: 'status.reason', reason: 'Evicted' }, 1
    ));
  }
  for (const status of allStatuses(pod)) {
    const containerLocator = { ...podLocator, container: status.name };
    const waiting = status.state?.waiting;
    const terminated = status.state?.terminated ?? status.lastState?.terminated;
    const waitingMap = {
      ImagePullBackOff: ['image_pull_backoff', '容器镜像拉取处于退避状态。'],
      ErrImagePull: ['image_pull_error', '容器镜像拉取失败。'],
      InvalidImageName: ['invalid_image_name', '容器镜像名称无效。'],
      CreateContainerConfigError: ['container_config_error', '容器配置依赖无法解析。'],
      CrashLoopBackOff: ['crash_loop_backoff', '容器重复启动失败并进入退避。'],
    };
    if (waitingMap[waiting?.reason]) {
      const [predicate, description] = waitingMap[waiting.reason];
      values.push(observation(
        predicate, true, description,
        { ...containerLocator, field: 'status.containerStatuses.state.waiting.reason', reason: waiting.reason }, 1
      ));
    }
    if (terminated && Number.isInteger(terminated.exitCode) && terminated.exitCode !== 0) {
      values.push(observation(
        'container_exit_nonzero', true, '容器最近一次以非零退出码结束。',
        {
          ...containerLocator,
          field: 'status.containerStatuses.state.terminated.exitCode',
          exit_code: terminated.exitCode,
        }, 1
      ));
    }
    if (terminated?.reason === 'OOMKilled') {
      values.push(observation(
        'container_oom_killed', true, '容器最近一次因内存不足被终止。',
        { ...containerLocator, field: 'status.containerStatuses.state.terminated.reason', reason: 'OOMKilled' }, 1
      ));
    }
    const restartCount = Number(status.restartCount ?? 0);
    if (restartCount > 0) {
      values.push(observation(
        'restart_count', restartCount, 'Kubernetes API 报告的容器重启次数。',
        { ...containerLocator, field: 'status.containerStatuses.restartCount' }, 1
      ));
    }
  }
  return values;
}

function eventObservations(events, pod) {
  const values = [];
  for (const event of events.items ?? []) {
    if (event.type !== 'Warning') continue;
    const reason = event.reason ?? '';
    const message = event.note ?? event.message ?? '';
    const eventLocator = locator('Event', event, {
      namespace: event.metadata?.namespace,
      name: event.metadata?.name,
      uid: event.metadata?.uid,
      resource_version: event.metadata?.resourceVersion,
      regarding_uid: event.regarding?.uid ?? event.involvedObject?.uid ?? pod.metadata?.uid,
      reason,
      count: event.series?.count ?? event.count ?? 1,
    });
    if (reason === 'FailedMount' || reason === 'FailedAttachVolume') {
      values.push(observation('volume_mount_failed', true, 'Kubernetes Event 报告卷挂载失败。', eventLocator, 0.9));
    }
    if (/readiness probe failed/i.test(message)) {
      values.push(observation('readiness_probe_failed', true, 'Kubernetes Event 报告就绪探针失败。', eventLocator, 0.85));
    }
    if (/liveness probe failed/i.test(message)) {
      values.push(observation('liveness_probe_failed', true, 'Kubernetes Event 报告存活探针失败。', eventLocator, 0.85));
    }
  }
  return values;
}

function stableHash(value) {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

function anonymizer(enabled) {
  return enabled
    ? (value) => `anon-${stableHash(String(value)).slice(0, 12)}`
    : (value) => value;
}

export function buildBundle(pod, events, version, options = {}) {
  const anonymize = anonymizer(Boolean(options.anonymize));
  const namespace = anonymize(pod.metadata?.namespace ?? 'default');
  const name = anonymize(pod.metadata?.name ?? 'unknown-pod');
  const uid = anonymize(pod.metadata?.uid ?? `${namespace}/${name}`);
  const capturedAt = options.capturedAt ?? new Date().toISOString();
  const serverVersion = version.serverVersion?.gitVersion;
  if (!serverVersion) throw new Error('kubectl version did not return serverVersion.gitVersion');

  const normalizedPod = structuredClone(pod);
  normalizedPod.metadata = { ...normalizedPod.metadata, namespace, name, uid };
  for (const status of allStatuses(normalizedPod)) status.name = anonymize(status.name);
  const normalizedEvents = structuredClone(events);
  for (const event of normalizedEvents.items ?? []) {
    event.metadata = {
      ...event.metadata,
      namespace: anonymize(event.metadata?.namespace ?? namespace),
      name: anonymize(event.metadata?.name ?? 'event'),
      uid: anonymize(event.metadata?.uid ?? 'event'),
    };
    if (event.regarding?.uid) event.regarding.uid = uid;
    if (event.involvedObject?.uid) event.involvedObject.uid = uid;
  }

  const podObservations = statusObservations(normalizedPod);
  const warningObservations = eventObservations(normalizedEvents, normalizedPod);
  if (podObservations.length === 0 && warningObservations.length === 0) {
    throw new Error('No supported Pod diagnostic observations were found');
  }
  const items = [];
  if (podObservations.length > 0) {
    items.push({
      external_id: `pod-status:${uid}:${pod.metadata?.resourceVersion ?? stableHash(pod).slice(0, 12)}`,
      source_type: 'kubernetes_api',
      captured_at: capturedAt,
      observations: podObservations,
    });
  }
  if (warningObservations.length > 0) {
    const eventIdentity = (normalizedEvents.items ?? []).map((event) => ({
      uid: event.metadata?.uid,
      resourceVersion: event.metadata?.resourceVersion,
    }));
    items.push({
      external_id: `pod-events:${uid}:${stableHash(eventIdentity).slice(0, 16)}`,
      source_type: 'kubernetes_event',
      captured_at: capturedAt,
      observations: warningObservations,
    });
  }
  return {
    schema_version: 'observation-bundle/1.0',
    domain_pack: PACK,
    event_type: EVENT_TYPE,
    target_version: serverVersion,
    subject: {
      type: 'kubernetes_pod',
      label: `${namespace}/${name}`,
      attributes: { namespace, pod_name: name, uid, anonymized: Boolean(options.anonymize) },
    },
    evidence_items: items,
  };
}
