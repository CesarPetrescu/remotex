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
          color="var(--accent)"
          points={history.cpu}
          max={100}
          online={online}
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
          color="var(--accent-blue)"
          points={history.mem}
          max={100}
          online={online}
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
          color="var(--accent-green)"
          points={history.gpu}
          max={100}
          online={online && !!current?.gpu}
          disabled={!current?.gpu}
        />
        <TelemetryCard
          label="NETWORK"
          valueMain={null}
          extras={
            <div className="telemetry-net-row">
              <span className="net-up">
                ↑ {formatBitsPerSec(current?.network?.up_bps)}
              </span>
              <span className="net-down">
                ↓ {formatBitsPerSec(current?.network?.down_bps)}
              </span>
            </div>
          }
          color="var(--accent-violet)"
          points={history.down}
          secondaryPoints={history.up}
          max={Math.max(1, ...history.up, ...history.down)}
          online={online}
          fixedMaxOff
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
  extras,
  color,
  points,
  secondaryPoints,
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
      {(valueMain || valueSide) && (
        <div className="telemetry-card-row">
          {valueMain && <span className="telemetry-card-main">{valueMain}</span>}
          {valueSide && <span className="telemetry-card-side">{valueSide}</span>}
        </div>
      )}
      {extras}
      <div className="telemetry-card-graph">
        {secondaryPoints && secondaryPoints.length > 0 && (
          <Sparkline
            points={secondaryPoints}
            max={max}
            color="var(--accent-pink)"
            height={48}
            className="sparkline-layer"
          />
        )}
        <Sparkline points={points || []} max={max} color={color} height={48} />
      </div>
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
