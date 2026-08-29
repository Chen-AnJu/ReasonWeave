import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { copyFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { projectedPod } from '../src/cli.mjs';
import { buildBundle } from '../src/kubernetes.mjs';

const runFile = promisify(execFile);

const pod = {
  apiVersion: 'v1',
  metadata: {
    name: 'payment-api-7f8d', namespace: 'production', uid: 'uid-secret', resourceVersion: '42',
  },
  spec: {
    containers: [{
      name: 'api',
      env: [{ name: 'DATABASE_PASSWORD', value: 'must-not-leak' }],
    }],
  },
  status: {
    containerStatuses: [{
      name: 'api', restartCount: 3,
      state: { waiting: { reason: 'ImagePullBackOff', message: 'private-registry/internal' } },
      lastState: { terminated: { reason: 'OOMKilled', exitCode: 137 } },
    }],
  },
};

const events = {
  apiVersion: 'v1',
  items: [{
    apiVersion: 'v1', type: 'Warning', reason: 'Unhealthy',
    message: 'Readiness probe failed: token=must-not-leak',
    metadata: { name: 'event.1', namespace: 'production', uid: 'event-uid', resourceVersion: '7' },
    involvedObject: { uid: 'uid-secret' },
  }],
};

test('normalizes supported Pod facts without retaining raw secrets or documents', () => {
  const bundle = buildBundle(pod, events, { serverVersion: { gitVersion: 'v1.37.0' } }, {
    capturedAt: '2026-08-28T00:00:00Z', anonymize: true,
  });
  const encoded = JSON.stringify(bundle);

  assert.equal(bundle.domain_pack, 'kubernetes-pod-diagnostics/1.0.0');
  assert.equal(bundle.target_version, 'v1.37.0');
  assert.equal(bundle.subject.attributes.anonymized, true);
  assert.match(bundle.subject.label, /^anon-/);
  assert.ok(bundle.evidence_items.flatMap((item) => item.observations)
    .some((value) => value.predicate === 'image_pull_backoff'));
  assert.ok(bundle.evidence_items.flatMap((item) => item.observations)
    .some((value) => value.predicate === 'container_oom_killed'));
  assert.ok(bundle.evidence_items.flatMap((item) => item.observations)
    .some((value) => value.predicate === 'readiness_probe_failed'));
  assert.doesNotMatch(encoded, /must-not-leak|DATABASE_PASSWORD|private-registry|uid-secret/);
});

test('projects only Pod metadata and status primitives instead of reading the Pod spec', async () => {
  const calls = [];
  const run = async (_base, args) => {
    calls.push(args);
    const expression = args.at(-1);
    if (expression.includes('.apiVersion')) return 'v1\tproduction\tpayment-api\tuid-1\t42\t';
    if (expression.includes('.status.conditions')) return 'PodScheduled\tFalse\tUnschedulable\n';
    if (expression.includes('.status.initContainerStatuses')) return '';
    return 'api\t3\tImagePullBackOff\t\t\t137\tOOMKilled\n';
  };

  const projected = await projectedPod(['--context', 'test'], 'payment-api', 'production', run);

  assert.equal(calls.length, 4);
  assert.ok(calls.every((args) => args.includes('--output')));
  assert.ok(calls.every((args) => args.at(-1).startsWith('jsonpath=')));
  assert.ok(calls.every((args) => !args.includes('json')));
  assert.equal(projected.spec, undefined);
  assert.equal(projected.metadata.uid, 'uid-1');
  assert.equal(projected.status.conditions[0].reason, 'Unschedulable');
  assert.equal(projected.status.containerStatuses[0].state.waiting.reason, 'ImagePullBackOff');
  assert.equal(projected.status.containerStatuses[0].lastState.terminated.reason, 'OOMKilled');
});

test('does not turn an unknown scheduling condition or zero restarts into a positive fact', () => {
  const quietPod = {
    apiVersion: 'v1',
    metadata: { name: 'quiet', namespace: 'default', uid: 'uid-quiet', resourceVersion: '1' },
    status: {
      conditions: [{ type: 'PodScheduled', status: 'False', reason: 'UnknownSchedulerState' }],
      containerStatuses: [{ name: 'app', restartCount: 0, state: { running: {} }, lastState: {} }],
    },
  };
  assert.throws(
    () => buildBundle(quietPod, { apiVersion: 'v1', items: [] }, {
      serverVersion: { gitVersion: 'v1.36.1' },
    }, { capturedAt: '2026-08-28T00:00:00Z' }),
    /No supported Pod diagnostic observations/,
  );
});

test('starts Kubernetes-only CLI usage without installing the cold-holding CSV dependency', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rw-evidence-kubernetes-only-'));
  try {
    await copyFile(new URL('../src/cli.mjs', import.meta.url), join(root, 'cli.mjs'));
    await copyFile(new URL('../src/kubernetes.mjs', import.meta.url), join(root, 'kubernetes.mjs'));
    const { stdout } = await runFile(process.execPath, [join(root, 'cli.mjs'), '--help'], {
      encoding: 'utf8',
    });
    assert.match(stdout, /rw-evidence kubernetes collect/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
