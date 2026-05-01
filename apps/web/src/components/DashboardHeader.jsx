import { SCREENS, STATUS } from '../config';

const STATUS_LABELS = {
  [STATUS.Idle]: 'idle',
  [STATUS.Opening]: 'opening',
  [STATUS.Connecting]: 'connecting',
  [STATUS.Connected]: 'connected',
  [STATUS.Disconnected]: 'disconnected',
  [STATUS.Error]: 'error',
};

export function DashboardHeader({
  state,
  onMenuClick,
  onToggleTelemetry,
  onDashboard,
}) {
  const label = STATUS_LABELS[state.status] || 'idle';
  const isLive =
    state.status === STATUS.Connected || state.status === STATUS.Connecting;
  // W11: only blink the brand cursor while we're handshaking. Solid amber
  // otherwise — the constant blink at the corner of the eye is annoying.
  const cursorBlinking =
    state.status === STATUS.Connecting || state.status === STATUS.Opening;
  return (
    <header className="dashboard-header">
      <div className="dashboard-header-left">
        {onMenuClick && (
          <button
            type="button"
            className="icon-button menu-button"
            onClick={onMenuClick}
            aria-label="Open sidebar"
          >
            <span aria-hidden="true">☰</span>
          </button>
        )}
        {/* W8: brand IS the home button. The redundant ⌂ Dashboard pill
            we shipped earlier was confusing — three home affordances. */}
        <button
          type="button"
          className="brand-button"
          onClick={onDashboard}
          title="Dashboard"
        >
          <span
            className={`brand-cursor${cursorBlinking ? ' blinking' : ''}`}
            aria-hidden="true"
          >▍</span>
          <span className="brand">REMOTEX</span>
        </button>
        <span className={`status-pill ${isLive ? 'is-live' : ''}`}>
          <span className={`tag-dot ${isLive ? 'ok' : ''}`} />
          {label}
        </span>
      </div>

      <div className="dashboard-header-right">
        {onToggleTelemetry && (
          <button
            type="button"
            className="icon-button header-telemetry-toggle"
            onClick={onToggleTelemetry}
            aria-label="Toggle telemetry"
            title="Telemetry"
          >
            <span aria-hidden="true">◔</span>
            <span className="header-telemetry-label">Telemetry</span>
          </button>
        )}
      </div>
    </header>
  );
}
