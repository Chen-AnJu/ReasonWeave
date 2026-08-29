#!/usr/bin/env node

import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { realpathSync } from 'node:fs';
import { link, unlink, writeFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { buildBundle } from './kubernetes.mjs';

const runFile = promisify(execFile);

async function atomicWriteNew(outputPath, content) {
  const temporary = `${outputPath}.${process.pid}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporary, content, { encoding: 'utf8', flag: 'wx' });
    await link(temporary, outputPath);
  } finally {
    await unlink(temporary).catch((error) => {
      if (error?.code !== 'ENOENT') throw error;
    });
  }
}

function usage() {
  return `ReasonWeave Evidence CLI

Usage:
  rw-evidence kubernetes collect --pod <name> [--namespace <namespace>]
    [--context <context>] [--kubeconfig <path>] [--anonymize] [--out <bundle.json>]

  rw-evidence cold-holding collect --event-ir <event-ir.json>
    --sources <sources.json> --telemetry <telemetry.csv> [--out <bundle.json>]

Collectors run locally and never send input data to an external service. The
Kubernetes collector only runs kubectl get/version and never reads Secrets,
environment-variable values, ServiceAccount tokens, or container logs.`;
}

function option(args, name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback;
}

async function kubectl(base, args) {
  const stdout = await kubectlText(base, args);
  return JSON.parse(stdout);
}

async function kubectlText(base, args) {
  const { stdout } = await runFile('kubectl', [...base, ...args], {
    encoding: 'utf8',
    timeout: 30_000,
    maxBuffer: 20 * 1024 * 1024,
    windowsHide: true,
  });
  return stdout;
}

function rows(value) {
  return value.split(/\r?\n/).filter((line) => line.length > 0).map((line) => line.split('\t'));
}

function terminated(exitCode, reason) {
  if (!exitCode && !reason) return undefined;
  return {
    ...(exitCode ? { exitCode: Number(exitCode) } : {}),
    ...(reason ? { reason } : {}),
  };
}

function containerStatus([name, restartCount, waitingReason, exitCode, reason, lastExitCode, lastReason]) {
  const currentTerminated = terminated(exitCode, reason);
  const previousTerminated = terminated(lastExitCode, lastReason);
  return {
    name,
    restartCount: Number(restartCount || 0),
    state: {
      ...(waitingReason ? { waiting: { reason: waitingReason } } : {}),
      ...(currentTerminated ? { terminated: currentTerminated } : {}),
    },
    lastState: previousTerminated ? { terminated: previousTerminated } : {},
  };
}

export async function projectedPod(base, podName, namespace, run = kubectlText) {
  // Deliberately request only metadata/status primitives. A full Pod JSON response
  // would expose spec.env values and Secret references to the collector process.
  const metadataPath = 'jsonpath={.apiVersion}{"\\t"}{.metadata.namespace}{"\\t"}{.metadata.name}{"\\t"}{.metadata.uid}{"\\t"}{.metadata.resourceVersion}{"\\t"}{.status.reason}';
  const conditionPath = 'jsonpath={range .status.conditions[*]}{.type}{"\\t"}{.status}{"\\t"}{.reason}{"\\n"}{end}';
  const initStatusPath = 'jsonpath={range .status.initContainerStatuses[*]}{.name}{"\\t"}{.restartCount}{"\\t"}{.state.waiting.reason}{"\\t"}{.state.terminated.exitCode}{"\\t"}{.state.terminated.reason}{"\\t"}{.lastState.terminated.exitCode}{"\\t"}{.lastState.terminated.reason}{"\\n"}{end}';
  const containerStatusPath = 'jsonpath={range .status.containerStatuses[*]}{.name}{"\\t"}{.restartCount}{"\\t"}{.state.waiting.reason}{"\\t"}{.state.terminated.exitCode}{"\\t"}{.state.terminated.reason}{"\\t"}{.lastState.terminated.exitCode}{"\\t"}{.lastState.terminated.reason}{"\\n"}{end}';
  const command = ['get', 'pod', podName, '--namespace', namespace, '--output'];
  const [metadataText, conditionsText, initStatusesText, statusesText] = await Promise.all([
    run(base, [...command, metadataPath]),
    run(base, [...command, conditionPath]),
    run(base, [...command, initStatusPath]),
    run(base, [...command, containerStatusPath]),
  ]);
  const [apiVersion, projectedNamespace, name, uid, resourceVersion, reason] = metadataText.split('\t');
  if (!uid) throw new Error('Pod status projection is missing metadata.uid');
  return {
    apiVersion: apiVersion || 'v1',
    metadata: { namespace: projectedNamespace || namespace, name: name || podName, uid, resourceVersion },
    status: {
      ...(reason ? { reason } : {}),
      conditions: rows(conditionsText).map(([type, status, conditionReason]) => ({
        type, status, reason: conditionReason,
      })),
      initContainerStatuses: rows(initStatusesText).map(containerStatus),
      containerStatuses: rows(statusesText).map(containerStatus),
    },
  };
}

async function collect(args) {
  const podName = option(args, '--pod');
  if (!podName) throw new Error('--pod is required');
  const namespace = option(args, '--namespace', 'default');
  const base = [];
  const context = option(args, '--context');
  const kubeconfig = option(args, '--kubeconfig');
  if (context) base.push('--context', context);
  if (kubeconfig) base.push('--kubeconfig', kubeconfig);

  const pod = await projectedPod(base, podName, namespace);
  const uid = pod.metadata?.uid;
  if (!uid) throw new Error('Pod response is missing metadata.uid');
  const [events, version] = await Promise.all([
    kubectl(base, [
      'get', 'events', '--namespace', namespace,
      '--field-selector', `involvedObject.uid=${uid}`, '--output', 'json',
    ]),
    kubectl(base, ['version', '--output', 'json']),
  ]);
  const bundle = buildBundle(pod, events, version, { anonymize: args.includes('--anonymize') });
  const output = `${JSON.stringify(bundle, null, 2)}\n`;
  const outputPath = option(args, '--out');
  if (outputPath) {
    await atomicWriteNew(outputPath, output);
    process.stdout.write(`${outputPath}\n`);
  } else {
    process.stdout.write(output);
  }
}

async function collectColdHolding(args) {
  const { collectColdHoldingBundle } = await import('./cold-holding.mjs');
  const eventIrPath = option(args, '--event-ir');
  const sourcesPath = option(args, '--sources');
  const telemetryPath = option(args, '--telemetry');
  if (!eventIrPath) throw new Error('--event-ir is required');
  if (!sourcesPath) throw new Error('--sources is required');
  if (!telemetryPath) throw new Error('--telemetry is required');
  const bundle = await collectColdHoldingBundle({ eventIrPath, sourcesPath, telemetryPath });
  const output = `${JSON.stringify(bundle, null, 2)}\n`;
  const outputPath = option(args, '--out');
  if (outputPath) {
    await atomicWriteNew(outputPath, output);
    process.stdout.write(`${outputPath}\n`);
  } else {
    process.stdout.write(output);
  }
}

async function main() {
  const [domain, command, ...args] = process.argv.slice(2);
  if (!domain || domain === '--help' || domain === '-h') return process.stdout.write(`${usage()}\n`);
  if (domain === 'kubernetes' && command === 'collect') return collect(args);
  if (domain === 'cold-holding' && command === 'collect') return collectColdHolding(args);
  throw new Error(`Unknown command: ${[domain, command].filter(Boolean).join(' ')}\n\n${usage()}`);
}

const invokedAsMain = process.argv[1]
  && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));

if (invokedAsMain) {
  main().catch((error) => {
    process.stderr.write(`rw-evidence: ${error.message}\n`);
    process.exitCode = 1;
  });
}
