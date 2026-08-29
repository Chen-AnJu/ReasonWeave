import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, open, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { collectColdHoldingBundle } from '../src/cold-holding.mjs';

const runFile = promisify(execFile);

const EVENT_START = '2026-08-01T00:00:00Z';
const EVENT_END = '2026-08-01T01:00:00Z';

function eventIr(overrides = {}) {
  return {
    schema_version: 'eventir/0.1',
    event: {
      type: 'cold_holding_temperature_excursion',
      title: '一号冷藏间温度异常',
      domain_pack: 'cold-holding-excursion-diagnostics/1.0.0',
      occurred_at: { start: EVENT_START, end: EVENT_END },
    },
    subjects: [{
      type: 'cold_holding_unit',
      label: 'site-a/unit-1',
      attributes: {
        site_id: 'site-a',
        unit_id: 'unit-1',
        unit_type: 'walk_in_cooler',
        temperature_limit_c: 5,
        minimum_excursion_minutes: 10,
        maximum_sample_gap_minutes: 10,
        sensor_tolerance_c: 1,
        policy_reference: '现场冷藏运行阈值 v1',
        ...overrides,
      },
    }],
  };
}

function sources(extra = []) {
  return {
    sources: [
      { source_id: 'logger-1', source_type: 'fixed_temperature_logger', sensor_role: 'air', calibration_status: 'valid' },
      { source_id: 'controller-1', source_type: 'equipment_controller', sensor_role: 'controller', calibration_status: 'not_applicable' },
      ...extra,
    ],
  };
}

function temperatureRows(source = 'logger-1') {
  return [
    ['2026-08-01T00:00:00Z', source, 'air_temperature', '4', 'C'],
    ['2026-08-01T00:10:00Z', source, 'air_temperature', '6', 'C'],
    ['2026-08-01T00:15:00Z', source, 'air_temperature', '7', 'C'],
    ['2026-08-01T00:20:00Z', source, 'air_temperature', '8', 'C'],
    ['2026-08-01T00:25:00Z', source, 'air_temperature', '7', 'C'],
    ['2026-08-01T00:30:00Z', source, 'air_temperature', '6', 'C'],
    ['2026-08-01T00:35:00Z', source, 'air_temperature', '4', 'C'],
  ];
}

function stateRows(metric, values, source = 'controller-1') {
  const times = ['00:10:00', '00:15:00', '00:20:00', '00:25:00', '00:30:00'];
  return values.map((value, index) => [`2026-08-01T${times[index]}Z`, source, metric, String(value), '']);
}

function csv(rows) {
  return `timestamp,source_id,metric,value,unit\n${rows.map((row) => row.join(',')).join('\n')}\n`;
}

async function collect({ event = eventIr(), sourceDocument = sources(), rows, telemetryText, limits }) {
  const directory = await mkdtemp(join(tmpdir(), 'rw-cold-holding-'));
  const eventIrPath = join(directory, 'event-ir.json');
  const sourcesPath = join(directory, 'sources.json');
  const telemetryPath = join(directory, 'telemetry.csv');
  await Promise.all([
    writeFile(eventIrPath, JSON.stringify(event), 'utf8'),
    writeFile(sourcesPath, JSON.stringify(sourceDocument), 'utf8'),
    writeFile(telemetryPath, telemetryText ?? csv(rows), 'utf8'),
  ]);
  try {
    return await collectColdHoldingBundle({ eventIrPath, sourcesPath, telemetryPath, limits });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function predicates(bundle) {
  return new Map(bundle.evidence_items[0].observations.map((value) => [value.predicate, value.value]));
}

test('derives an operational heat-load cause and only emits summary evidence', async () => {
  const rows = [
    ...temperatureRows(),
    ...stateRows('door_open', [true, true, true, true, false]),
    ...stateRows('power_available', [true, true, true, true, true]),
  ];
  const first = await collect({ rows });
  const second = await collect({ rows });
  const facts = predicates(first);

  assert.equal(facts.get('temperature_excursion_detected'), true);
  assert.equal(facts.get('operational_heat_load_detected'), true);
  assert.equal(facts.get('prolonged_door_open_overlaps_excursion'), true);
  assert.equal(facts.get('power_stable_through_excursion'), true);
  assert.equal(first.evidence_items[0].external_id, second.evidence_items[0].external_id);
  assert.equal(first.evidence_items[0].source_type, 'collector_derived');
  assert.match(JSON.stringify(first), /"source_ids":\["logger-1"/);
  assert.doesNotMatch(JSON.stringify(first), /timestamp,source_id|telemetry\.csv/);
});

test('derives power/control interruption without merging a fragmentary second source', async () => {
  const rows = [
    ...temperatureRows(),
    ...stateRows('power_available', [false, false, false, true, true]),
    ['2026-08-01T00:15:00Z', 'controller-2', 'power_available', 'false', ''],
  ];
  const bundle = await collect({
    rows,
    sourceDocument: sources([
      { source_id: 'controller-2', source_type: 'equipment_controller', sensor_role: 'controller', calibration_status: 'not_applicable' },
    ]),
  });
  const facts = predicates(bundle);

  assert.equal(facts.get('power_or_control_interruption_detected'), true);
  assert.equal(facts.get('power_interruption_overlaps_excursion'), true);
  assert.equal(facts.has('power_stable_through_excursion'), false);
});

test('derives refrigeration response failure from a sustained command without recovery', async () => {
  const hotRows = temperatureRows().slice(0, -1).map((row, index) => (
    index === 0 ? row : [row[0], row[1], row[2], '8', row[4]]
  ));
  const bundle = await collect({
    rows: [
      ...hotRows,
      ...stateRows('compressor_running', [true, true, true, true, true]),
      ['2026-08-01T00:20:00Z', 'controller-1', 'compressor_or_fan_alarm', 'true', ''],
    ],
  });
  const facts = predicates(bundle);

  assert.equal(facts.get('refrigeration_response_failure_detected'), true);
  assert.equal(facts.get('compressor_or_fan_alarm_present'), true);
  assert.equal(facts.get('cooling_command_without_recovery'), true);
});

test('derives measurement anomaly from an invalid logger and reference disagreement', async () => {
  const referenceRows = [
    ['2026-08-01T00:10:00Z', 'probe-1', 'reference_temperature', '4', 'C'],
    ['2026-08-01T00:20:00Z', 'probe-1', 'reference_temperature', '4', 'C'],
    ['2026-08-01T00:30:00Z', 'probe-1', 'reference_temperature', '4', 'C'],
  ];
  const bundle = await collect({
    rows: [...temperatureRows(), ...referenceRows],
    sourceDocument: {
      sources: [
        { source_id: 'logger-1', source_type: 'fixed_temperature_logger', sensor_role: 'air', calibration_status: 'expired' },
        { source_id: 'probe-1', source_type: 'calibrated_manual_probe', sensor_role: 'reference', calibration_status: 'valid' },
      ],
    },
  });
  const facts = predicates(bundle);

  assert.equal(facts.get('measurement_system_anomaly_detected'), true);
  assert.equal(facts.get('reference_probe_disagrees'), true);
  assert.equal(facts.get('sensor_calibration_invalid_or_expired'), true);
  assert.equal(facts.get('single_sensor_spike_without_peer_confirmation'), true);
});

test('keeps a temperature excursion as evidence-insufficient when no cause fact exists', async () => {
  const bundle = await collect({ rows: temperatureRows() });
  const facts = predicates(bundle);

  assert.equal(facts.get('temperature_excursion_detected'), true);
  assert.equal(facts.has('power_or_control_interruption_detected'), false);
  assert.equal(facts.has('refrigeration_response_failure_detected'), false);
  assert.equal(facts.has('operational_heat_load_detected'), false);
  assert.equal(facts.has('measurement_system_anomaly_detected'), false);
});

test('parses RFC 4180 quoted newlines and explicit non-UTC offsets', async () => {
  const sourceId = 'logger,\r\nprimary';
  const telemetryText = [
    'timestamp,source_id,metric,value,unit',
    '2026-08-01T08:10:00+08:00,"logger,',
    'primary",air_temperature,6,C',
    '2026-08-01T08:20:00+08:00,"logger,',
    'primary",air_temperature,7,C',
    '2026-08-01T08:30:00+08:00,"logger,',
    'primary",air_temperature,6,C',
    '2026-08-01T08:35:00+08:00,"logger,',
    'primary",air_temperature,4,C',
    '',
  ].join('\r\n');
  const bundle = await collect({
    telemetryText,
    sourceDocument: { sources: [{ source_id: sourceId, source_type: 'fixed_temperature_logger', sensor_role: 'air', calibration_status: 'valid' }] },
  });

  assert.equal(predicates(bundle).get('temperature_excursion_detected'), true);
  assert.ok(bundle.evidence_items[0].observations.some((value) => value.source_locator.source_ids.includes(sourceId)));
});

test('aligns the fixed CC BY 4.0 Zenodo temperature and door-action excerpt', async () => {
  const fixture = join(import.meta.dirname, '../../../fixtures/cold-holding/zenodo-15130001');
  const bundle = await collectColdHoldingBundle({
    eventIrPath: join(fixture, 'event-ir.json'),
    sourcesPath: join(fixture, 'sources.json'),
    telemetryPath: join(fixture, 'telemetry.csv'),
  });
  const facts = predicates(bundle);

  assert.equal(facts.get('temperature_excursion_detected'), true);
  assert.equal(facts.get('prolonged_door_open_overlaps_excursion'), true);
  assert.equal(facts.get('warm_load_introduced_before_excursion'), true);
  assert.equal(facts.get('operational_heat_load_detected'), true);
});

test('rejects bad units, conflicting duplicates, out-of-window rows, and missing time ranges', async () => {
  await assert.rejects(
    collect({ rows: [['2026-08-01T00:10:00Z', 'logger-1', 'air_temperature', '8', 'F']] }),
    /temperature unit must be C/,
  );
  await assert.rejects(
    collect({ rows: [
      ['2026-08-01T00:10:00Z', 'logger-1', 'air_temperature', '8', 'C'],
      ['2026-08-01T00:10:00Z', 'logger-1', 'air_temperature', '9', 'C'],
    ] }),
    /conflicts with a duplicate timestamp/,
  );
  await assert.rejects(
    collect({ rows: [['2026-08-01T02:00:00Z', 'logger-1', 'air_temperature', '8', 'C']] }),
    /outside the EventIR time range/,
  );
  await assert.rejects(
    collect({ rows: [
      ['2026-08-01T00:20:00Z', 'logger-1', 'air_temperature', '8', 'C'],
      ['2026-08-01T00:10:00Z', 'logger-1', 'air_temperature', '8', 'C'],
    ] }),
    /out of order/,
  );
  await assert.rejects(
    collect({ rows: [['2026-08-01T00:10:00', 'logger-1', 'air_temperature', '8', 'C']] }),
    /explicit offset/,
  );
  const missingRange = eventIr();
  delete missingRange.event.occurred_at;
  await assert.rejects(
    collect({ event: missingRange, rows: temperatureRows() }),
    /must contain occurred_at\.start and occurred_at\.end/,
  );
});

test('writes output atomically and never overwrites an existing bundle', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rw-cold-holding-cli-'));
  const eventIrPath = join(directory, 'event-ir.json');
  const sourcesPath = join(directory, 'sources.json');
  const telemetryPath = join(directory, 'telemetry.csv');
  const outputPath = join(directory, 'bundle.json');
  await Promise.all([
    writeFile(eventIrPath, JSON.stringify(eventIr()), 'utf8'),
    writeFile(sourcesPath, JSON.stringify(sources()), 'utf8'),
    writeFile(telemetryPath, csv(temperatureRows()), 'utf8'),
  ]);
  const args = [
    join(import.meta.dirname, '../src/cli.mjs'), 'cold-holding', 'collect',
    '--event-ir', eventIrPath, '--sources', sourcesPath, '--telemetry', telemetryPath,
    '--out', outputPath,
  ];
  try {
    await runFile(process.execPath, args, { windowsHide: true });
    const first = await readFile(outputPath, 'utf8');
    await assert.rejects(runFile(process.execPath, args, { windowsHide: true }), /EEXIST|already exists/);
    assert.equal(await readFile(outputPath, 'utf8'), first);
    assert.deepEqual((await readdir(directory)).filter((name) => name.endsWith('.tmp')), []);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('enforces the production file and row limits before producing a bundle', async () => {
  await assert.rejects(
    collect({ rows: temperatureRows().slice(0, 3), limits: { maxRows: 2 } }),
    /2 row limit/,
  );

  const directory = await mkdtemp(join(tmpdir(), 'rw-cold-holding-size-'));
  const eventIrPath = join(directory, 'event-ir.json');
  const sourcesPath = join(directory, 'sources.json');
  const telemetryPath = join(directory, 'telemetry.csv');
  await Promise.all([
    writeFile(eventIrPath, JSON.stringify(eventIr()), 'utf8'),
    writeFile(sourcesPath, JSON.stringify(sources()), 'utf8'),
  ]);
  const handle = await open(telemetryPath, 'w');
  await handle.truncate(250 * 1024 * 1024 + 1);
  await handle.close();
  try {
    await assert.rejects(
      collectColdHoldingBundle({ eventIrPath, sourcesPath, telemetryPath }),
      /250 MiB limit/,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('keeps a large telemetry collection within a bounded Node heap', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rw-cold-holding-streaming-'));
  const eventIrPath = join(directory, 'event-ir.json');
  const sourcesPath = join(directory, 'sources.json');
  const telemetryPath = join(directory, 'telemetry.csv');
  const outputPath = join(directory, 'bundle.json');
  await Promise.all([
    writeFile(eventIrPath, JSON.stringify(eventIr()), 'utf8'),
    writeFile(sourcesPath, JSON.stringify(sources()), 'utf8'),
  ]);
  const handle = await open(telemetryPath, 'w');
  try {
    await handle.write('timestamp,source_id,metric,value,unit\n');
    const start = Date.parse(EVENT_START);
    const rows = 200_000;
    const chunkSize = 5_000;
    for (let offset = 0; offset < rows; offset += chunkSize) {
      const lines = [];
      for (let index = offset; index < Math.min(rows, offset + chunkSize); index += 1) {
        lines.push(`${new Date(start + index * 18).toISOString()},logger-1,air_temperature,6,C`);
      }
      await handle.write(`${lines.join('\n')}\n`);
    }
  } finally {
    await handle.close();
  }

  try {
    await runFile(process.execPath, [
      '--max-old-space-size=64',
      join(import.meta.dirname, '../src/cli.mjs'), 'cold-holding', 'collect',
      '--event-ir', eventIrPath, '--sources', sourcesPath, '--telemetry', telemetryPath,
      '--out', outputPath,
    ], { windowsHide: true, timeout: 60_000 });
    const bundle = JSON.parse(await readFile(outputPath, 'utf8'));
    assert.equal(predicates(bundle).get('temperature_excursion_detected'), true);
    assert.equal(predicates(bundle).get('telemetry_sample_count'), 200_000);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
