import { useState } from 'react';
import { STATUS } from '../config';
import { ThemeToggle } from './ThemeToggle';

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
  api,
  onMenuClick,
  rightView = 'telemetry',
  onRightView,
  leftCollapsed = false,
  onToggleLeftCollapsed,
  hasPendingPrompt = false,
  pendingPromptCount = 0,
  onDashboard,
}) {
  const [pings, setPings] = useState({});
  const [pinging, setPinging] = useState(false);
  const label = STATUS_LABELS[state.status] || 'idle';
  const isLive = state.status === STATUS.Connected;
  const onlineCount = state.hosts.filter((host) => host.online).length;

  const refreshPings = async () => {
    if (pinging || !api) return;
    setPinging(true);
    const results = await Promise.all(
      state.hosts.filter((host) => host.online).map(async (host) => {
        try {
          return [host.id, await api.pingHost(host.id)];
        } catch {
          return [host.id, null];
        }
      }),
    );
    setPings(Object.fromEntries(results));
    setPinging(false);
  };

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
          <img className="brand-logo" src="/favicon-192.png" alt="" />
          <span className="brand">REMOTEX</span>
        </button>
        <div className="daemon-status" onMouseEnter={refreshPings}>
          <button
            type="button"
            className={`status-pill ${isLive ? 'is-live' : ''}`}
            onFocus={refreshPings}
            aria-describedby="daemon-status-details"
          >
            <span className={`tag-dot ${isLive ? 'ok' : ''}`} aria-hidden="true" />
            <span role="status" aria-live="polite">{label}</span>
          </button>
          <div
            id="daemon-status-details"
            className="daemon-popover"
            role="tooltip"
            aria-busy={pinging}
          >
            <div className="daemon-popover-title">
              <span>Daemons</span>
              <span>{onlineCount}/{state.hosts.length} online</span>
            </div>
            {state.hosts.length === 0 ? (
              <div className="daemon-popover-empty">No daemons registered</div>
            ) : state.hosts.map((host) => (
              <div className="daemon-popover-row" key={host.id}>
                <span className={`tag-dot ${host.online ? 'ok' : ''}`} aria-hidden="true" />
                <span className="daemon-popover-name" title={host.hostname || host.nickname}>
                  {host.nickname || host.hostname || host.id}
                </span>
                <span className={`daemon-popover-ping${host.online ? '' : ' offline'}`}>
                  {daemonPingLabel(host, pings[host.id], pinging)}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="dashboard-header-right">
        <ThemeToggle />
        {onRightView && (
          <div className="header-tools" aria-label="Tools">
            <HeaderTool
              id="telemetry"
              label={hasPendingPrompt ? 'Telemetry · pending prompt' : 'Telemetry'}
              icon={(
                <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
                  <polyline
                    points="1,9 4.5,9 6.5,3.5 9.5,12.5 11.5,9 15,9"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.6"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              )}
              active={rightView !== 'off'}
              onClick={() => onRightView(rightView === 'off' ? 'telemetry' : 'off')}
              badge={hasPendingPrompt ? String(pendingPromptCount) : null}
            />
          </div>
        )}
      </div>
    </header>
  );
}

function daemonPingLabel(host, ping, pinging) {
  if (!host.online) return 'offline';
  if (Number.isFinite(ping)) return ping === 0 ? '<1 ms' : `${ping} ms`;
  if (ping === null) return 'unreachable';
  return pinging ? 'checking…' : '—';
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
