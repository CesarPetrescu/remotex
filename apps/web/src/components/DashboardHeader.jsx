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
  rightView = 'telemetry',
  onRightView,
  leftCollapsed = false,
  onToggleLeftCollapsed,
  hasOrchestrator = false,
  onDashboard,
}) {
  const label = STATUS_LABELS[state.status] || 'idle';
  const isLive =
    state.status === STATUS.Connected || state.status === STATUS.Connecting;
  const cursorBlinking =
    state.status === STATUS.Connecting || state.status === STATUS.Opening;
  return (
    <header className="dashboard-header">
      <div className="dashboard-header-left">
        {onMenuClick && (
          <button
            type="button"
            className="icon-button menu-button mobile-only"
            onClick={onMenuClick}
            aria-label="Open sidebar"
          >
            <span aria-hidden="true">☰</span>
          </button>
        )}
        {onToggleLeftCollapsed && (
          <button
            type="button"
            className="icon-button hosts-collapse-button desktop-only"
            onClick={onToggleLeftCollapsed}
            aria-label={leftCollapsed ? 'Expand hosts' : 'Collapse hosts'}
            title={leftCollapsed ? 'Show hosts panel' : 'Hide hosts panel'}
          >
            <span aria-hidden="true">{leftCollapsed ? '▶' : '◀'}</span>
          </button>
        )}
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
        {onRightView && (
          <div className="right-view-tabs" role="tablist" aria-label="Right sidebar">
            <RightHeaderTab
              id="plan"
              label="Plan"
              active={rightView === 'plan'}
              onClick={() => onRightView('plan')}
              badge={hasOrchestrator}
            />
            <RightHeaderTab
              id="telemetry"
              label="Telemetry"
              active={rightView === 'telemetry'}
              onClick={() => onRightView('telemetry')}
            />
            <RightHeaderTab
              id="off"
              label="Hide"
              active={rightView === 'off'}
              onClick={() => onRightView('off')}
            />
          </div>
        )}
        {onToggleTelemetry && (
          <button
            type="button"
            className="icon-button header-telemetry-toggle mobile-only"
            onClick={onToggleTelemetry}
            aria-label="Toggle right sidebar"
            title="Right sidebar"
          >
            <span aria-hidden="true">◔</span>
          </button>
        )}
      </div>
    </header>
  );
}

function RightHeaderTab({ id, label, active, onClick, badge }) {
  return (
    <button
      type="button"
      className={`right-view-tab${active ? ' active' : ''}`}
      onClick={onClick}
      aria-selected={active}
      role="tab"
    >
      {label}
      {badge && <span className="right-view-tab-badge" aria-label="active" />}
    </button>
  );
}
