import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { parse } from 'csv-parse';

const PACK = 'cold-holding-excursion-diagnostics/1.0.0';
const EVENT_TYPE = 'cold_holding_temperature_excursion';
const ALGORITHM = 'cold-holding-collector/1.0.0';
const MAX_FILE_BYTES = 250 * 1024 * 1024;
const MAX_ROWS = 5_000_000;
const REQUIRED_COLUMNS = ['timestamp', 'source_id', 'metric', 'value', 'unit'];
const TEMPERATURE_METRICS = new Set(['air_temperature', 'product_temperature', 'reference_temperature']);
const BOOLEAN_METRICS = new Set([
  'door_open', 'power_available', 'controller_online', 'compressor_running', 'defrost_active',
  'compressor_or_fan_alarm', 'warm_load_introduced', 'storage_airflow_obstructed',
  'coil_or_airflow_component_fault',
]);
const SOURCE_TYPES = new Set([
  'calibrated_manual_probe', 'equipment_controller', 'fixed_temperature_logger',
  'maintenance_inspection', 'operator_report',
]);
const SENSOR_ROLES = new Set(['air', 'product', 'reference', 'controller', 'inspection']);
const CALIBRATION_STATUSES = new Set(['valid', 'expired', 'invalid', 'not_applicable']);
const RFC3339_WITH_OFFSET = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;

function finiteNumber(value, label) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`${label} must be a finite number`);
  return number;
}

function integer(value, label) {
  const number = finiteNumber(value, label);
  if (!Number.isInteger(number)) throw new Error(`${label} must be an integer`);
  return number;
}

function parseTimestamp(value, label) {
  if (!RFC3339_WITH_OFFSET.test(value)) throw new Error(`${label} must be RFC 3339 with an explicit offset`);
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) throw new Error(`${label} is not a valid timestamp`);
  return timestamp;
}

function parseBoolean(value, label) {
  if (value === 'true') return true;
  if (value === 'false') return false;
  throw new Error(`${label} must be true or false`);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function observation(predicate, value, description, locator, confidence = 1) {
  return { predicate, value, confidence, description, source_locator: locator };
}

function eventContract(document) {
  if (document?.schema_version !== 'eventir/0.1') throw new Error('event-ir.json must use eventir/0.1');
  if (document?.event?.domain_pack !== PACK) throw new Error(`event-ir.json must reference ${PACK}`);
  if (document?.event?.type !== EVENT_TYPE) throw new Error(`event-ir.json must use ${EVENT_TYPE}`);
  const startText = document?.event?.occurred_at?.start;
  const endText = document?.event?.occurred_at?.end;
  if (!startText || !endText) throw new Error('event-ir.json must contain occurred_at.start and occurred_at.end');
  const start = parseTimestamp(startText, 'event occurred_at.start');
  const end = parseTimestamp(endText, 'event occurred_at.end');
  if (end <= start) throw new Error('event occurred_at.end must be after occurred_at.start');
  if (!Array.isArray(document.subjects) || document.subjects.length !== 1) {
    throw new Error('event-ir.json must contain exactly one subject');
  }
  const subject = document.subjects[0];
  if (subject?.type !== 'cold_holding_unit') throw new Error('event subject must be cold_holding_unit');
  const attributes = subject.attributes ?? {};
  for (const field of ['site_id', 'unit_id', 'unit_type', 'policy_reference']) {
    if (typeof attributes[field] !== 'string' || attributes[field].trim() === '') {
      throw new Error(`event subject ${field} is required`);
    }
  }
  const limit = finiteNumber(attributes.temperature_limit_c, 'temperature_limit_c');
  const minimumMinutes = integer(attributes.minimum_excursion_minutes, 'minimum_excursion_minutes');
  const maxGapMinutes = integer(attributes.maximum_sample_gap_minutes, 'maximum_sample_gap_minutes');
  const tolerance = finiteNumber(attributes.sensor_tolerance_c, 'sensor_tolerance_c');
  if (limit < -50 || limit > 20) throw new Error('temperature_limit_c is outside the supported range');
  if (minimumMinutes < 1 || minimumMinutes > 1440) throw new Error('minimum_excursion_minutes is outside the supported range');
  if (maxGapMinutes < 1 || maxGapMinutes > 1440) throw new Error('maximum_sample_gap_minutes is outside the supported range');
  if (tolerance <= 0 || tolerance > 10) throw new Error('sensor_tolerance_c is outside the supported range');
  return {
    subject,
    start,
    end,
    startText: new Date(start).toISOString(),
    endText: new Date(end).toISOString(),
    limit,
    minimumMinutes,
    maxGapMinutes,
    tolerance,
  };
}

function sourceContract(document) {
  if (!Array.isArray(document?.sources) || document.sources.length === 0) {
    throw new Error('sources.json must contain a non-empty sources array');
  }
  const sources = new Map();
  for (const value of document.sources) {
    if (typeof value?.source_id !== 'string' || value.source_id.trim() === '') {
      throw new Error('Every source requires source_id');
    }
    if (sources.has(value.source_id)) throw new Error(`Duplicate source_id: ${value.source_id}`);
    if (!SOURCE_TYPES.has(value.source_type)) throw new Error(`Unknown source_type: ${value.source_type}`);
    const role = value.sensor_role ?? (value.source_type === 'equipment_controller' ? 'controller' : 'inspection');
    if (!SENSOR_ROLES.has(role)) throw new Error(`Unknown sensor_role for ${value.source_id}: ${role}`);
    const calibration = value.calibration_status ?? 'not_applicable';
    if (!CALIBRATION_STATUSES.has(calibration)) {
      throw new Error(`Unknown calibration_status for ${value.source_id}: ${calibration}`);
    }
    if (['calibrated_manual_probe', 'fixed_temperature_logger'].includes(value.source_type)
        && !['air', 'product', 'reference'].includes(role)) {
      throw new Error(`Temperature source ${value.source_id} requires an air, product, or reference role`);
    }
    sources.set(value.source_id, { ...value, sensor_role: role, calibration_status: calibration });
  }
  return sources;
}

function hashPassThrough(hash) {
  return new Transform({
    transform(chunk, _encoding, callback) {
      hash.update(chunk);
      callback(null, chunk);
    },
  });
}

function utf8Decoder() {
  const decoder = new TextDecoder('utf-8', { fatal: true });
  return new Transform({
    transform(chunk, _encoding, callback) {
      try {
        callback(null, decoder.decode(chunk, { stream: true }));
      } catch {
        callback(new Error('telemetry.csv must be valid UTF-8'));
      }
    },
    flush(callback) {
      try {
        callback(null, decoder.decode());
      } catch {
        callback(new Error('telemetry.csv must be valid UTF-8'));
      }
    },
  });
}

async function scanTelemetry(path, sources, event, limits = {}, onRecord = () => {}) {
  const maxFileBytes = limits.maxFileBytes ?? MAX_FILE_BYTES;
  const maxRows = limits.maxRows ?? MAX_ROWS;
  const info = await stat(path);
  if (!info.isFile()) throw new Error('telemetry path must be a file');
  if (info.size > maxFileBytes) throw new Error('telemetry.csv exceeds the 250 MiB limit');
  const hash = createHash('sha256');
  const lastBySeries = new Map();
  let dataRows = 0;
  let uniqueRows = 0;
  const parser = parse({
    bom: true,
    info: true,
    columns: (header) => {
      if (header.length !== REQUIRED_COLUMNS.length
          || REQUIRED_COLUMNS.some((column) => !header.includes(column))) {
        throw new Error(`telemetry.csv header must contain exactly: ${REQUIRED_COLUMNS.join(',')}`);
      }
      return header;
    },
    relax_column_count: false,
    skip_empty_lines: true,
    trim: true,
  });
  await pipeline(
    createReadStream(path),
    hashPassThrough(hash),
    utf8Decoder(),
    parser,
    async (values) => {
      for await (const parsed of values) {
        dataRows += 1;
        const value = parsed.record;
        const row = parsed.info.lines;
        if (dataRows > maxRows) throw new Error(`telemetry.csv exceeds the ${maxRows} row limit`);
        const source = sources.get(value.source_id);
        if (!source) throw new Error(`telemetry row ${row} references unknown source_id: ${value.source_id}`);
        const timestamp = parseTimestamp(value.timestamp, `telemetry row ${row} timestamp`);
        if (timestamp < event.start || timestamp > event.end) {
          throw new Error(`telemetry row ${row} is outside the EventIR time range`);
        }
        const seriesKey = `${value.source_id}\u0000${value.metric}`;
        const previous = lastBySeries.get(seriesKey);
        if (previous && timestamp < previous.timestamp) {
          throw new Error(`telemetry row ${row} is out of order for ${value.source_id}/${value.metric}`);
        }
        let parsedValue;
        if (TEMPERATURE_METRICS.has(value.metric)) {
          if (value.unit !== 'C') throw new Error(`telemetry row ${row} temperature unit must be C`);
          parsedValue = finiteNumber(value.value, `telemetry row ${row} value`);
          if (parsedValue < -100 || parsedValue > 100) throw new Error(`telemetry row ${row} temperature is outside the supported range`);
        } else if (BOOLEAN_METRICS.has(value.metric)) {
          if (value.unit !== '') throw new Error(`telemetry row ${row} boolean metric unit must be empty`);
          parsedValue = parseBoolean(value.value, `telemetry row ${row} value`);
        } else {
          throw new Error(`telemetry row ${row} uses unsupported metric: ${value.metric}`);
        }
        if (previous && timestamp === previous.timestamp) {
          if (previous.value !== JSON.stringify(parsedValue)) {
            throw new Error(`telemetry row ${row} conflicts with a duplicate timestamp record`);
          }
          continue;
        }
        lastBySeries.set(seriesKey, { timestamp, value: JSON.stringify(parsedValue) });
        uniqueRows += 1;
        onRecord({
          row,
          timestamp,
          source,
          sourceId: value.source_id,
          metric: value.metric,
          value: parsedValue,
          seriesKey,
        });
      }
    },
  );
  if (uniqueRows === 0) throw new Error('telemetry.csv does not contain data rows');
  return { rowCount: uniqueRows, hash: hash.digest('hex'), bytes: info.size };
}

function updateMetricSummary(summaries, key, record) {
  let summary = summaries.get(key);
  if (!summary) {
    summary = { sourceIds: new Set(), firstRow: record.row, lastRow: record.row };
    summaries.set(key, summary);
  }
  summary.sourceIds.add(record.sourceId);
  summary.firstRow = Math.min(summary.firstRow, record.row);
  summary.lastRow = Math.max(summary.lastRow, record.row);
}

function betterCandidate(candidate, existing) {
  if (!existing) return true;
  const duration = candidate.end - candidate.start;
  const existingDuration = existing.end - existing.start;
  return duration > existingDuration
    || (duration === existingDuration && candidate.peak > existing.peak)
    || (duration === existingDuration && candidate.peak === existing.peak
      && candidate.seriesKey < existing.seriesKey);
}

function closeExcursion(state, minimumMs, best) {
  if (!state.current) return best;
  const candidate = { ...state.current, seriesKey: state.seriesKey, sourceId: state.sourceId, metric: state.metric };
  state.current = undefined;
  if (candidate.end - candidate.start < minimumMs) return best;
  return betterCandidate(candidate, best) ? candidate : best;
}

async function firstTelemetryPass(path, sources, event, limits) {
  const maxGapMs = event.maxGapMinutes * 60_000;
  const minimumMs = event.minimumMinutes * 60_000;
  const temperatureStates = new Map();
  const metricSummaries = new Map();
  let best;
  let hasTemperatureGap = false;
  let invalidCalibration = false;
  const scan = await scanTelemetry(path, sources, event, limits, (record) => {
    updateMetricSummary(metricSummaries, 'all', record);
    updateMetricSummary(metricSummaries, record.metric, record);
    if (!TEMPERATURE_METRICS.has(record.metric)) return;
    let state = temperatureStates.get(record.seriesKey);
    if (!state) {
      state = {
        seriesKey: record.seriesKey,
        sourceId: record.sourceId,
        source: record.source,
        metric: record.metric,
        count: 0,
        lastTimestamp: undefined,
        current: undefined,
      };
      temperatureStates.set(record.seriesKey, state);
    }
    state.count += 1;
    if (state.lastTimestamp != null && record.timestamp - state.lastTimestamp > maxGapMs) {
      hasTemperatureGap = true;
      best = closeExcursion(state, minimumMs, best);
    }
    state.lastTimestamp = record.timestamp;
    if (['expired', 'invalid'].includes(record.source.calibration_status)) invalidCalibration = true;
    if (record.value > event.limit) {
      if (!state.current) {
        state.current = {
          start: record.timestamp,
          end: record.timestamp,
          last: record.timestamp,
          peak: record.value,
          firstRow: record.row,
          lastRow: record.row,
        };
      } else {
        state.current.end = record.timestamp;
        state.current.last = record.timestamp;
        state.current.peak = Math.max(state.current.peak, record.value);
        state.current.lastRow = record.row;
      }
    } else {
      best = closeExcursion(state, minimumMs, best);
    }
  });
  for (const state of temperatureStates.values()) best = closeExcursion(state, minimumMs, best);
  if (!best) throw new Error('No sustained temperature excursion was found in the EventIR time range');
  return { ...scan, best, temperatureStates, metricSummaries, hasTemperatureGap, invalidCalibration };
}

function nearestDisagreement(primary, reference, maxGapMs, start, end) {
  let maximum = 0;
  let pairs = 0;
  let index = 0;
  for (let primaryIndex = 0; primaryIndex < primary.length; primaryIndex += 1) {
    const timestamp = primary.timestamps[primaryIndex];
    if (timestamp < start || timestamp > end || reference.length === 0) continue;
    while (index + 1 < reference.length
      && Math.abs(reference.timestamps[index + 1] - timestamp) <= Math.abs(reference.timestamps[index] - timestamp)) {
      index += 1;
    }
    if (Math.abs(reference.timestamps[index] - timestamp) <= maxGapMs) {
      maximum = Math.max(maximum, Math.abs(reference.values[index] - primary.values[primaryIndex]));
      pairs += 1;
    }
  }
  return { maximum, pairs };
}

function locatorSummary(context, metrics) {
  const sourceIds = new Set();
  let firstRow;
  let lastRow;
  const selected = new Set();
  if (metrics.includes('all')) selected.add('all');
  for (const metric of metrics) {
    if (metric === 'temperature' || metric === 'timestamp' || metric === 'source.calibration_status') {
      for (const temperatureMetric of TEMPERATURE_METRICS) selected.add(temperatureMetric);
    } else if (metric !== 'all') {
      selected.add(metric);
    }
  }
  for (const key of selected) {
    const summary = context.metricSummaries.get(key);
    if (!summary) continue;
    for (const sourceId of summary.sourceIds) sourceIds.add(sourceId);
    firstRow = firstRow == null ? summary.firstRow : Math.min(firstRow, summary.firstRow);
    lastRow = lastRow == null ? summary.lastRow : Math.max(lastRow, summary.lastRow);
  }
  return { sourceIds, firstRow, lastRow };
}

function factLocator(context, metrics, specific) {
  const summary = specific ?? locatorSummary(context, metrics);
  return {
    kind: 'cold_holding_telemetry_summary',
    algorithm: ALGORITHM,
    event_ir_sha256: context.eventIrHash,
    telemetry_sha256: context.telemetryHash,
    sources_sha256: context.sourcesHash,
    event_window: { start: context.event.startText, end: context.event.endText },
    metrics,
    source_ids: [...summary.sourceIds].sort(),
    ...(summary.firstRow != null && summary.lastRow != null
      ? { source_rows: [summary.firstRow, summary.lastRow] }
      : {}),
  };
}

function addFact(output, predicate, description, context, metrics, specific) {
  output.push(observation(predicate, true, description, factLocator(context, metrics, specific)));
}

function updateBooleanState(state, record, start, end, maxGapMs) {
  if (state.previous) {
    const intervalStart = Math.max(start, state.previous.timestamp);
    const intervalEnd = Math.min(end, record.timestamp, state.previous.timestamp + maxGapMs);
    if (intervalEnd > intervalStart) {
      const duration = intervalEnd - intervalStart;
      state.covered += duration;
      if (state.previous.value) state.trueDuration += duration;
      else state.falseDuration += duration;
    }
  }
  state.previous = { timestamp: record.timestamp, value: record.value };
  state.count += 1;
}

function bestBooleanState(states, metric) {
  return [...states.values()]
    .filter((state) => state.metric === metric)
    .sort((left, right) => right.covered - left.covered || right.count - left.count)[0]
    ?? { covered: 0, trueDuration: 0, falseDuration: 0, count: 0 };
}

async function derive(path, sources, first, event, eventIrHash, sourcesHash, limits) {
  const maxGapMs = event.maxGapMinutes * 60_000;
  const minimumMs = event.minimumMinutes * 60_000;
  const excursion = first.best;
  const windowDuration = excursion.end - excursion.start;
  const temperatureBuffers = new Map();
  for (const [key, state] of first.temperatureStates) {
    if (key === excursion.seriesKey
        || state.source.sensor_role === 'reference'
        || state.metric === 'reference_temperature') {
      temperatureBuffers.set(key, {
        source: state.source,
        metric: state.metric,
        timestamps: new Float64Array(state.count),
        values: new Float64Array(state.count),
        length: 0,
      });
    }
  }
  const booleanStates = new Map();
  const peerSeries = new Set();
  let peerConfirms = false;
  let independentProductConfirms = false;
  let warmLoad = false;
  let airflow = false;
  let alarm = false;
  let componentFault = false;
  let finalPrimaryPoint;
  const second = await scanTelemetry(path, sources, event, limits, (record) => {
    const buffer = temperatureBuffers.get(record.seriesKey);
    if (buffer) {
      buffer.timestamps[buffer.length] = record.timestamp;
      buffer.values[buffer.length] = record.value;
      buffer.length += 1;
    }
    if (TEMPERATURE_METRICS.has(record.metric)) {
      if (record.seriesKey === excursion.seriesKey && record.timestamp >= excursion.start) {
        finalPrimaryPoint = record;
      }
      if (record.seriesKey !== excursion.seriesKey
          && record.timestamp >= excursion.start && record.timestamp <= excursion.end) {
        peerSeries.add(record.seriesKey);
        if (record.value > event.limit) peerConfirms = true;
      }
      if (record.source.source_type === 'calibrated_manual_probe'
          && record.source.sensor_role === 'product'
          && record.timestamp >= excursion.start && record.timestamp <= excursion.end
          && record.value > event.limit) {
        independentProductConfirms = true;
      }
      return;
    }
    let state = booleanStates.get(record.seriesKey);
    if (!state) {
      state = {
        metric: record.metric,
        covered: 0,
        trueDuration: 0,
        falseDuration: 0,
        count: 0,
        previous: undefined,
      };
      booleanStates.set(record.seriesKey, state);
    }
    updateBooleanState(state, record, excursion.start, excursion.end, maxGapMs);
    if (!record.value) return;
    if (record.metric === 'warm_load_introduced'
        && record.timestamp >= Math.max(event.start, excursion.start - minimumMs)
        && record.timestamp <= excursion.end) warmLoad = true;
    if (record.metric === 'storage_airflow_obstructed'
        && record.timestamp >= excursion.start && record.timestamp <= excursion.end) airflow = true;
    if (record.metric === 'compressor_or_fan_alarm'
        && record.timestamp >= excursion.start && record.timestamp <= excursion.end) alarm = true;
    if (record.metric === 'coil_or_airflow_component_fault'
        && record.timestamp >= excursion.start && record.timestamp <= excursion.end) componentFault = true;
  });
  if (second.hash !== first.hash || second.rowCount !== first.rowCount) {
    throw new Error('telemetry.csv changed while it was being collected');
  }
  const context = {
    event,
    eventIrHash,
    telemetryHash: first.hash,
    sourcesHash,
    metricSummaries: first.metricSummaries,
  };
  const output = [];
  const primarySpecific = {
    sourceIds: new Set([excursion.sourceId]),
    firstRow: excursion.firstRow,
    lastRow: excursion.lastRow,
  };
  addFact(output, 'temperature_excursion_detected', '在事件时间窗内检测到超过现场阈值且持续时间达标的温度区间。', context,
    ['air_temperature', 'product_temperature', 'reference_temperature'], primarySpecific);
  output.push(observation('observed_peak_temperature_c', excursion.peak,
    '持续异常区间内观察到的最高温度。', factLocator(context, ['temperature'], primarySpecific)));
  output.push(observation('observed_excursion_duration_minutes', windowDuration / 60_000,
    '持续异常区间的长度。', factLocator(context, ['temperature'], primarySpecific)));
  output.push(observation('telemetry_sample_count', first.rowCount,
    '本次本地采集器处理的有效遥测行数。', factLocator(context, ['all'])));

  const primaryValues = temperatureBuffers.get(excursion.seriesKey);
  let comparison = { maximum: 0, pairs: 0 };
  for (const [key, reference] of temperatureBuffers) {
    if (key === excursion.seriesKey) continue;
    const current = nearestDisagreement(
      primaryValues, reference, maxGapMs, excursion.start, excursion.end,
    );
    comparison = {
      maximum: Math.max(comparison.maximum, current.maximum),
      pairs: comparison.pairs + current.pairs,
    };
  }
  if (comparison.pairs > 0) {
    output.push(observation('observed_max_sensor_disagreement_c', comparison.maximum,
      '可对齐的固定记录器与参考探头读数之间的最大绝对差。',
      factLocator(context, ['air_temperature', 'reference_temperature'])));
  }
  const referenceDisagrees = comparison.pairs > 0 && comparison.maximum > event.tolerance;
  const singleSensorSpike = peerSeries.size > 0 && !peerConfirms;
  if (referenceDisagrees) addFact(output, 'reference_probe_disagrees', '固定记录器与参考探头的差异超过事件声明容差。', context,
    ['air_temperature', 'reference_temperature']);
  if (first.invalidCalibration) addFact(output, 'sensor_calibration_invalid_or_expired', '至少一个参与判断的温度来源声明校准无效或已过期。', context,
    ['source.calibration_status']);
  if (singleSensorSpike) addFact(output, 'single_sensor_spike_without_peer_confirmation', '一个传感器记录到异常，但同时间窗内其他传感器未确认。', context,
    ['temperature']);
  if (first.hasTemperatureGap) addFact(output, 'telemetry_gap_or_clock_anomaly', '温度序列存在超过事件允许值的采样缺口。', context,
    ['timestamp']);
  if (independentProductConfirms) addFact(output, 'independent_product_probe_confirms_excursion', '已校准独立产品探头在异常时间窗内确认温度超过现场阈值。', context,
    ['product_temperature']);
  if (referenceDisagrees || first.invalidCalibration || singleSensorSpike || first.hasTemperatureGap) {
    addFact(output, 'measurement_system_anomaly_detected', '校准、探头一致性或数据连续性证据表明测量系统可能异常。', context,
      ['temperature', 'timestamp', 'source.calibration_status']);
  }

  const power = bestBooleanState(booleanStates, 'power_available');
  const controller = bestBooleanState(booleanStates, 'controller_online');
  const defrost = bestBooleanState(booleanStates, 'defrost_active');
  const door = bestBooleanState(booleanStates, 'door_open');
  const compressor = bestBooleanState(booleanStates, 'compressor_running');
  const powerCoverage = windowDuration > 0 ? power.covered / windowDuration : 0;
  const doorCoverage = windowDuration > 0 ? door.covered / windowDuration : 0;
  const powerInterrupted = power.falseDuration > 0;
  const controllerOffline = controller.falseDuration > 0;
  const abnormalDefrost = defrost.trueDuration >= minimumMs;
  if (powerInterrupted) addFact(output, 'power_interruption_overlaps_excursion', '供电不可用状态与持续温度异常时间重叠。', context,
    ['power_available', 'temperature']);
  if (controllerOffline) addFact(output, 'controller_offline_overlaps_excursion', '控制器离线状态与持续温度异常时间重叠。', context,
    ['controller_online', 'temperature']);
  if (abnormalDefrost) addFact(output, 'abnormal_defrost_overlaps_excursion', '除霜状态覆盖温度异常的时间达到现场最短持续阈值。', context,
    ['defrost_active', 'temperature']);
  if (powerInterrupted || controllerOffline || abnormalDefrost) {
    addFact(output, 'power_or_control_interruption_detected', '供电、控制器或除霜时间线提供了控制中断证据。', context,
      ['power_available', 'controller_online', 'defrost_active']);
  }
  if (powerCoverage >= 0.95 && !powerInterrupted) {
    addFact(output, 'power_stable_through_excursion', '供电状态覆盖至少 95% 的异常区间且未记录中断。', context,
      ['power_available']);
  }

  const prolongedDoor = door.trueDuration >= minimumMs;
  if (prolongedDoor) addFact(output, 'prolonged_door_open_overlaps_excursion', '门开启状态与异常重叠时间达到现场最短持续阈值。', context,
    ['door_open', 'temperature']);
  if (warmLoad) addFact(output, 'warm_load_introduced_before_excursion', '操作记录显示异常前或异常期间有较热物品装入。', context,
    ['warm_load_introduced']);
  if (airflow) addFact(output, 'storage_airflow_obstructed', '维护或操作记录显示储藏空间气流受阻。', context,
    ['storage_airflow_obstructed']);
  if (prolongedDoor || warmLoad || airflow) {
    addFact(output, 'operational_heat_load_detected', '开门、装载或气流记录提供了运行热负荷证据。', context,
      ['door_open', 'warm_load_introduced', 'storage_airflow_obstructed']);
  }
  if (doorCoverage >= 0.95 && door.trueDuration === 0 && !warmLoad) {
    addFact(output, 'door_closed_and_no_load_change', '门状态覆盖至少 95% 的异常区间，且未记录开门或装载变化。', context,
      ['door_open', 'warm_load_introduced']);
  }

  const recoveryDrop = finalPrimaryPoint ? excursion.peak - finalPrimaryPoint.value : 0;
  output.push(observation('observed_recovery_drop_c', recoveryDrop,
    '峰值之后至当前温度序列末端的降温幅度。', factLocator(context, ['temperature'], primarySpecific)));
  const noRecovery = compressor.covered > 0 && compressor.trueDuration / compressor.covered >= 0.80
    && recoveryDrop < event.tolerance;
  const recoveredBelowLimit = finalPrimaryPoint
    && finalPrimaryPoint.timestamp > excursion.end && finalPrimaryPoint.value <= event.limit;
  const normalDoorRecovery = door.trueDuration > 0 && recoveredBelowLimit;
  if (alarm) addFact(output, 'compressor_or_fan_alarm_present', '控制器或维护记录报告压缩机或风机报警。', context,
    ['compressor_or_fan_alarm']);
  if (noRecovery) addFact(output, 'cooling_command_without_recovery', '压缩机运行记录覆盖异常区间，但温度恢复幅度小于声明容差。', context,
    ['compressor_running', 'temperature']);
  if (componentFault) addFact(output, 'coil_or_airflow_component_fault_observed', '维护检查记录报告盘管、风机或其他制冷部件异常。', context,
    ['coil_or_airflow_component_fault']);
  if (normalDoorRecovery) addFact(output, 'normal_recovery_after_door_close', '开门事件结束后温度恢复至现场阈值以内。', context,
    ['door_open', 'temperature']);
  if (alarm || noRecovery || componentFault) {
    addFact(output, 'refrigeration_response_failure_detected', '控制器、温度响应或维护检查提供了制冷设备响应不足证据。', context,
      ['compressor_running', 'compressor_or_fan_alarm', 'coil_or_airflow_component_fault', 'temperature']);
  }
  return output;
}

export async function collectColdHoldingBundle({ eventIrPath, sourcesPath, telemetryPath, limits }) {
  const [eventBytes, sourceBytes] = await Promise.all([readFile(eventIrPath), readFile(sourcesPath)]);
  let eventDocument;
  let sourceDocument;
  try {
    eventDocument = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(eventBytes));
  } catch {
    throw new Error('event-ir.json must be valid UTF-8 JSON');
  }
  try {
    sourceDocument = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(sourceBytes));
  } catch {
    throw new Error('sources.json must be valid UTF-8 JSON');
  }
  const event = eventContract(eventDocument);
  const sources = sourceContract(sourceDocument);
  const telemetry = await firstTelemetryPass(telemetryPath, sources, event, limits);
  const eventIrHash = sha256(eventBytes);
  const sourcesHash = sha256(sourceBytes);
  const observations = await derive(
    telemetryPath, sources, telemetry, event, eventIrHash, sourcesHash, limits,
  );
  const identity = sha256(`${eventIrHash}\n${telemetry.hash}\n${sourcesHash}`);
  return {
    schema_version: 'observation-bundle/1.0',
    domain_pack: PACK,
    event_type: EVENT_TYPE,
    subject: structuredClone(event.subject),
    evidence_items: [{
      external_id: `cold-holding:${identity.slice(0, 24)}`,
      source_type: 'collector_derived',
      captured_at: event.endText,
      observations,
    }],
  };
}
