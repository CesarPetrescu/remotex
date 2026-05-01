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
  const onDashboardScreen = state.screen === SCREENS.Hosts;
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
        <button
          type="button"
          className="brand-button"
          onClick={onDashboard}
          title="Dashboard"
        >
          <span className="brand-cursor" aria-hidden="true">▍</span>
          <span className="brand">REMOTEX</span>
        </button>
        {!onDashboardScreen && onDashboard && (
          <button
            type="button"
            className="header-dashboard-pill"
            onClick={onDashboard}
            title="Back to dashboard (keeps the active session running)"
          >
            ⌂ Dashboard
          </button>
        )}
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
          </button>
        )}
      </div>
    </header>
  );
}
