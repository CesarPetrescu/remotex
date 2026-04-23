import { ModelPicker, EffortPicker, PermissionsPicker } from './Pickers';
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
  onToggleTelemetry,
  onModelChange,
  onEffortChange,
  onPermissionsChange,
}) {
  const label = STATUS_LABELS[state.status] || 'idle';
  const isLive =
    state.status === STATUS.Connected || state.status === STATUS.Connecting;
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
        <div className="brand-mark" aria-hidden="true">
          ◈
        </div>
        <div className="brand">REMOTEX</div>
        <span className={`status-pill ${isLive ? 'is-live' : ''}`}>
          <span className={`tag-dot ${isLive ? 'ok' : ''}`} />
          {label}
        </span>
      </div>

      <div className="dashboard-header-right">
        <HeaderPicker label="Model">
          <ModelPicker value={state.model} onChange={onModelChange} />
        </HeaderPicker>
        <HeaderPicker label="Effort">
          <EffortPicker model={state.model} value={state.effort} onChange={onEffortChange} />
        </HeaderPicker>
        <HeaderPicker label="Perms">
          <PermissionsPicker value={state.permissions} onChange={onPermissionsChange} />
        </HeaderPicker>
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

function HeaderPicker({ label, children }) {
  return (
    <div className="header-picker">
      <span className="header-picker-label">{label}</span>
      {children}
    </div>
  );
}
