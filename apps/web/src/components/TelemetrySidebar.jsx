import { Sparkline } from './Sparkline';

const EMPTY_HISTORY = { cpu: [], mem: [], gpu: [], up: [], down: [] };

export function TelemetrySidebar({ telemetry, selectedHost, onClose }) {
  const current = telemetry?.current;
  const history = telemetry?.history || EMPTY_HISTORY;
  const fresh = telemetry?.lastUpdate
    ? Math.max(0, Math.floor((Date.now() - telemetry.lastUpdate) / 1000))
    : null;
  const live = fresh !== null && fresh <= 10;
  const online = !!selectedHost?.online;

  return (
    <aside className="telemetry-sidebar" aria-label="System telemetry">
      <div className="sidebar-head telemetry-head">
        <div>
          <div className="telemetry-title">SYSTEM TELEMETRY</div>
          <div className="telemetry-sub">
            {selectedHost ? selectedHost.nickname : 'no host selected'}
          </div>
        </div>
        <div className="telemetry-tags">
          <span className={`tag ${live ? 'tag-live' : ''}`}>
            <span className={`tag-dot ${live ? 'ok' : ''}`} />
            {live ? 'Live' : online ? 'Stale' : 'Offline'}
          </span>
          <span className="tag-muted">3s</span>
          {onClose && (
            <button
              type="button"
              className="sidebar-close"
              onClick={onClose}
              aria-label="Close telemetry"
            >
              ×
            </button>
          )}
        </div>
      </div>

      <div className="telemetry-cards">
        <TelemetryCard
          label="CPU"
          valueMain={current?.cpu ? `${formatPercent(current.cpu.percent)}%` : '—'}
          valueSide={current?.cpu ? `${current.cpu.cores} cores` : null}
          note={
            current?.cpu?.temp_c != null
              ? `${Math.round(current.cpu.temp_c)}°C package`
              : 'processor load'
          }
          color="var(--accent)"
          points={history.cpu}
          max={100}
          online={online}
          summary={summarizeSeries(history.cpu, (value) => `${formatPercent(value)}%`)}
        />
        <TelemetryCard
          label="RAM"
          valueMain={
            current?.memory ? `${formatPercent(current.memory.percent)}%` : '—'
          }
          valueSide={
            current?.memory
              ? `${formatBytes(current.memory.used_bytes)} / ${formatBytes(current.memory.total_bytes)}`
              : null
          }
          note={current?.memory ? 'resident working set' : 'memory pressure'}
          color="var(--accent-blue)"
          points={history.mem}
          max={100}
          online={online}
          summary={summarizeSeries(history.mem, (value) => `${formatPercent(value)}%`)}
        />
        <TelemetryCard
          label="GPU"
          valueMain={
            current?.gpu?.percent != null
              ? `${formatPercent(current.gpu.percent)}%`
              : current?.gpu
                ? '—'
                : 'n/a'
          }
          valueSide={current?.gpu?.name || (current?.gpu ? 'GPU' : 'no GPU')}
          note={
            current?.gpu?.mem_total_mb
              ? `${formatMegabytes(current.gpu.mem_used_mb)} / ${formatMegabytes(current.gpu.mem_total_mb)} VRAM`
              : 'accelerator state'
          }
          color="var(--accent-green)"
          points={history.gpu}
          max={100}
          online={online && !!current?.gpu}
          disabled={!current?.gpu}
          summary={summarizeSeries(history.gpu, (value) => `${formatPercent(value)}%`)}
        />
        <TelemetryCard
          label="NETWORK"
          valueMain={null}
          extras={
            <div className="telemetry-net-row telemetry-chart-legend">
              <span className="telemetry-chart-legend-item net-up">
                <span className="telemetry-chart-swatch" />
                ↑ {formatBitsPerSec(current?.network?.up_bps)}
              </span>
              <span className="telemetry-chart-legend-item net-down">
                <span className="telemetry-chart-swatch" />
                ↓ {formatBitsPerSec(current?.network?.down_bps)}
              </span>
            </div>
          }
          note="3 second rolling transfer window"
          color="var(--accent-violet)"
          points={history.down}
          secondaryPoints={history.up}
          max={Math.max(1, ...history.up, ...history.down)}
          online={online}
          fixedMaxOff
          summary={summarizeNetwork(history.up, history.down)}
        />
      </div>

      <div className="telemetry-footer">
        <TelemetryStat label="Uptime" value={formatUptime(current?.uptime_s)} />
        <TelemetryStat
          label="Load Avg"
          value={
            current?.load_avg
              ? current.load_avg.map((v) => v.toFixed(2)).join(' ')
              : '—'
          }
        />
        <TelemetryStat
          label="Temp"
          value={
            current?.cpu?.temp_c != null
              ? `${Math.round(current.cpu.temp_c)}°C`
              : '—'
          }
        />
      </div>
    </aside>
  );
}

function TelemetryCard({
  label,
  valueMain,
  valueSide,
  note,
  extras,
  color,
  points,
  secondaryPoints,
  summary = [],
  max,
  online,
  disabled,
}) {
  return (
    <div className={`telemetry-card ${disabled ? 'is-disabled' : ''}`}>
      <div className="telemetry-card-head">
        <span className="telemetry-card-label">{label}</span>
        <span className={`telemetry-card-dot ${online ? 'ok' : ''}`} />
      </div>
      <div className="telemetry-card-meta">
        {(valueMain || valueSide) && (
          <div className="telemetry-card-row">
            {valueMain && <span className="telemetry-card-main">{valueMain}</span>}
            {valueSide && <span className="telemetry-card-side">{valueSide}</span>}
          </div>
        )}
        {note && <div className="telemetry-card-note">{note}</div>}
      </div>
      {extras}
      <div className="telemetry-card-graph">
        <Sparkline
          points={points || []}
          secondaryPoints={secondaryPoints || []}
          max={max}
          color={color}
          secondaryColor="var(--accent-pink)"
          height={70}
        />
      </div>
      {summary.length > 0 && (
        <div className="telemetry-card-summary">
          {summary.map((item) => (
            <div className="telemetry-summary-pill" key={`${label}-${item.label}`}>
              <span className="telemetry-summary-label">{item.label}</span>
              <span className="telemetry-summary-value">{item.value}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TelemetryStat({ label, value }) {
  return (
    <div className="telemetry-stat">
      <div className="telemetry-stat-label">{label}</div>
      <div className="telemetry-stat-value">{value}</div>
    </div>
  );
}

function formatPercent(n) {
  if (n == null || !Number.isFinite(n)) return '—';
  return n >= 10 ? Math.round(n).toString() : n.toFixed(1);
}

function formatBytes(bytes) {
  if (!bytes || !Number.isFinite(bytes)) return '—';
  const gb = bytes / (1024 ** 3);
  if (gb >= 1) return `${gb.toFixed(1)} GB`;
  const mb = bytes / (1024 ** 2);
  return `${mb.toFixed(0)} MB`;
}

function formatMegabytes(mb) {
  if (mb == null || !Number.isFinite(mb)) return '—';
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${Math.round(mb)} MB`;
}

function formatBitsPerSec(bps) {
  if (!bps || !Number.isFinite(bps)) return '0 bps';
  if (bps >= 1e9) return `${(bps / 1e9).toFixed(1)} Gbps`;
  if (bps >= 1e6) return `${(bps / 1e6).toFixed(1)} Mbps`;
  if (bps >= 1e3) return `${(bps / 1e3).toFixed(1)} kbps`;
  return `${Math.round(bps)} bps`;
}

function formatUptime(seconds) {
  if (seconds == null || !Number.isFinite(seconds)) return '—';
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function summarizeSeries(points, format) {
  const safe = (points || []).filter((value) => Number.isFinite(value));
  if (safe.length === 0) return [];
  return [
    { label: 'Now', value: format(safe[safe.length - 1]) },
    { label: 'Peak', value: format(Math.max(...safe)) },
    { label: 'Floor', value: format(Math.min(...safe)) },
  ];
}

function summarizeNetwork(upPoints, downPoints) {
  const up = (upPoints || []).filter((value) => Number.isFinite(value));
  const down = (downPoints || []).filter((value) => Number.isFinite(value));
  if (up.length === 0 && down.length === 0) return [];
  return [
    {
      label: 'Up peak',
      value: formatBitsPerSec(up.length ? Math.max(...up) : 0),
    },
    {
      label: 'Down peak',
      value: formatBitsPerSec(down.length ? Math.max(...down) : 0),
    },
    {
      label: 'Live sum',
      value: formatBitsPerSec((up[up.length - 1] || 0) + (down[down.length - 1] || 0)),
    },
  ];
}
