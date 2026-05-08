import { STATUS } from '../config';

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
  rightView = 'telemetry',
  onRightView,
  leftCollapsed = false,
  onToggleLeftCollapsed,
  hasGoal = false,
  pendingPromptCount = 0,
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
          <div className="header-tools" aria-label="Tools">
            {(hasGoal || pendingPromptCount > 0) && (
              <HeaderTool
                id="goal"
                label="Goal"
                icon="◎"
                active={rightView === 'goal'}
                onClick={() => onRightView(rightView === 'goal' ? 'off' : 'goal')}
                badge={pendingPromptCount > 0 ? String(pendingPromptCount) : hasGoal}
              />
            )}
            <HeaderTool
              id="telemetry"
              label="Telemetry"
              icon="▥"
              active={rightView === 'telemetry'}
              onClick={() => onRightView(rightView === 'telemetry' ? 'off' : 'telemetry')}
            />
          </div>
        )}
      </div>
    </header>
  );
}

function HeaderTool({ id, label, icon, active, onClick, badge }) {
  return (
    <button
      id={`header-tool-${id}`}
      type="button"
      className={`header-tool${active ? ' active' : ''}`}
      onClick={onClick}
      aria-pressed={active}
      aria-label={label}
      title={label}
    >
      <span aria-hidden="true">{icon}</span>
      {badge && (
        <span className="header-tool-badge" aria-label="active">
          {typeof badge === 'string' ? badge : ''}
        </span>
      )}
    </button>
  );
}
