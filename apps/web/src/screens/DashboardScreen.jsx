import { useEffect, useState } from 'react';

import { shortenCwd } from '../util/path';
import { relativeAge } from '../util/time';
import { STATUS } from '../config';

// Middle-column view when no session or search screen is active.
// Active Session card + Semantic Search + Quick Actions + Folder Selection.

const SEARCH_SUGGESTIONS = [
  'game of life',
  'rust interpreter',
  'relay architecture',
  'wasm',
  'sql',
  'codex flow',
];

export function DashboardScreen({
  state,
  selectedHost,
  telemetry,
  onOpenSession,
  onEndSession,
  onNewSession,
  onOpenOrchestrator,
  onOpenSearch,
  onSearchChange,
  onRunSearch,
  onBrowseFiles,
  onOpenFolderPicker,
  onStartInCwd,
  onRefreshThreads,
  onOpenManageHosts,
}) {
  return (
    <div className="dashboard-center">
      <ActiveSessionCard
        state={state}
        selectedHost={selectedHost}
        telemetry={telemetry}
        onOpenSession={onOpenSession}
        onEndSession={onEndSession}
      />
      <div className="dashboard-row">
        <SemanticSearchPanel
          query={state.searchQuery}
          onChange={onSearchChange}
          onSubmit={onRunSearch}
          onOpenSearch={onOpenSearch}
        />
        <QuickActionsPanel
          canStart={!!selectedHost?.online}
          onNewSession={onNewSession}
          onOpenOrchestrator={onOpenOrchestrator}
          onBrowseFiles={onBrowseFiles}
          onRefreshThreads={onRefreshThreads}
          onOpenManageHosts={onOpenManageHosts}
          threadCount={state.threads.length}
        />
      </div>
      <FolderSelectionPanel
        state={state}
        canStart={!!selectedHost?.online}
        onOpenFolderPicker={onOpenFolderPicker}
        onStartInCwd={onStartInCwd}
      />
    </div>
  );
}

function ActiveSessionCard({ state, selectedHost, telemetry, onOpenSession, onEndSession }) {
  const info = state.session;
  const hasSession = !!info && state.status !== STATUS.Idle;
  const agentTitle = deriveSessionTitle(state);
  const startedAt = info?.startedAt || state.session?.startedAt;
  const statusLabel = {
    [STATUS.Idle]: 'Idle',
    [STATUS.Opening]: 'Opening',
    [STATUS.Connecting]: 'Connecting',
    [STATUS.Connected]: 'Connected',
    [STATUS.Disconnected]: 'Disconnected',
    [STATUS.Error]: 'Error',
  }[state.status] || 'Idle';

  return (
    <section className="card card-active-session">
      <div className="card-head">
        <span className="card-eyebrow">ACTIVE SESSION</span>
        <span className={`card-dot ${hasSession ? 'ok' : ''}`} />
      </div>
      <h2 className="card-title">
        {hasSession ? agentTitle : 'No active session'}
      </h2>
      <div className="session-facts">
        <SessionFact
          label="Host"
          value={
            selectedHost ? (
              <span className="session-fact-value">
                <span className={`dot ${selectedHost.online ? 'ok' : ''}`} />
                <span>{selectedHost.nickname}</span>
              </span>
            ) : (
              '—'
            )
          }
        />
        <SessionFact
          label="CWD"
          value={info?.cwd ? shortenCwd(info.cwd) : state.browsePath || '—'}
          mono
        />
        <SessionFact label="Model" value={info?.model || state.model || 'default'} mono />
        <SessionFact label="State" value={statusLabel} tone={hasSession ? 'live' : 'idle'} />
        <SessionFact
          label="Started"
          value={
            startedAt
              ? relativeAge(Math.floor(startedAt / 1000))
              : hasSession
                ? 'just now'
                : '—'
          }
        />
        <SessionFact
          label="Host info"
          value={formatHostIdent(selectedHost, telemetry) || '—'}
          mono
        />
      </div>
      <div className="card-actions">
        <button
          type="button"
          className="btn-primary"
          onClick={onOpenSession}
          disabled={!hasSession}
        >
          Open session
        </button>
        <button
          type="button"
          className="btn-surface"
          onClick={() => {
            // W3: confirm before killing an in-flight turn — costly to re-do.
            if (state.pending) {
              const ok = window.confirm(
                'A turn is currently running. End the session anyway?\n' +
                'Use Cancel to keep it running in the background.',
              );
              if (!ok) return;
            }
            onEndSession();
          }}
          disabled={!hasSession}
        >
          End session
        </button>
      </div>
    </section>
  );
}

function SessionFact({ label, value, mono = false, tone }) {
  return (
    <div className="session-fact">
      <span className="session-fact-label">{label}</span>
      <span className={`session-fact-body ${mono ? 'mono' : ''} ${tone ? `tone-${tone}` : ''}`}>
        {value}
      </span>
    </div>
  );
}

function SemanticSearchPanel({ query, onChange, onSubmit, onOpenSearch }) {
  const [localQuery, setLocalQuery] = useState(query || '');
  useEffect(() => {
    setLocalQuery(query || '');
  }, [query]);

  const handleSubmit = (e) => {
    e.preventDefault();
    const q = localQuery.trim();
    if (!q) return;
    onChange?.(q);
    onSubmit?.(q);
  };

  return (
    <section className="card card-search">
      <div className="card-head">
        <span className="card-eyebrow">SEMANTIC CHAT SEARCH</span>
        <span className="card-hint" aria-hidden="true">ⓘ</span>
      </div>
      <form className="search-form" onSubmit={handleSubmit}>
        <input
          type="text"
          className="search-input"
          value={localQuery}
          onChange={(e) => setLocalQuery(e.target.value)}
          placeholder="Search chats by topic, code, or question…"
          spellCheck={false}
        />
        <button type="submit" className="btn-primary btn-full">
          Search chats
        </button>
      </form>
      <div className="search-try">
        <span className="search-try-label">Try searches like:</span>
        <div className="search-chips">
          {SEARCH_SUGGESTIONS.map((s) => (
            <button
              key={s}
              type="button"
              className="search-chip"
              onClick={() => {
                setLocalQuery(s);
                onChange?.(s);
                onSubmit?.(s);
              }}
            >
              {s}
            </button>
          ))}
        </div>
      </div>
      <button type="button" className="link-button" onClick={onOpenSearch}>
        Open full search →
      </button>
    </section>
  );
}

function QuickActionsPanel({
  canStart,
  onNewSession,
  onOpenOrchestrator,
  onBrowseFiles,
  onRefreshThreads,
  onOpenManageHosts,
  threadCount,
}) {
  return (
    <section className="card card-actions-panel">
      <div className="card-head">
        <span className="card-eyebrow">QUICK ACTIONS</span>
      </div>
      <div className="actions-grid">
        <ActionTile
          icon="+"
          title="New session"
          subtitle="Start a fresh codex thread"
          onClick={onNewSession}
          disabled={!canStart}
        />
        <ActionTile
          icon="⌘"
          title="Orchestrate"
          subtitle="Long task → DAG of subtasks"
          onClick={onOpenOrchestrator}
          disabled={!canStart || !onOpenOrchestrator}
        />
        <ActionTile
          icon="▤"
          title="Select folder"
          subtitle="Choose working directory"
          onClick={onBrowseFiles}
          disabled={!canStart}
        />
        <ActionTile
          icon="↻"
          title="Load session"
          subtitle={`${threadCount} previous session${threadCount === 1 ? '' : 's'}`}
          onClick={onRefreshThreads}
          disabled={!canStart}
        />
        <ActionTile
          icon="◈"
          title="Manage hosts"
          subtitle="Add or switch hosts"
          onClick={onOpenManageHosts}
        />
      </div>
    </section>
  );
}

function ActionTile({ icon, title, subtitle, onClick, disabled }) {
  return (
    <button
      type="button"
      className="action-tile"
      onClick={onClick}
      disabled={disabled}
    >
      <span className="action-tile-body">
        <span className="action-tile-title">{title}</span>
        <span className="action-tile-sub">{subtitle}</span>
      </span>
      <span className="action-tile-icon" aria-hidden="true">
        {icon}
      </span>
    </button>
  );
}

function FolderSelectionPanel({
  state,
  canStart,
  onOpenFolderPicker,
  onStartInCwd,
}) {
  const path = state.browsePath || '/';

  return (
    <section className="card card-folder">
      <div className="card-head card-head-split">
        <span className="card-eyebrow">WORKSPACE</span>
        <div className="card-head-actions">
          <button
            type="button"
            className="btn-surface btn-sm"
            onClick={onOpenFolderPicker}
            disabled={!canStart}
          >
            Browse…
          </button>
          <button
            type="button"
            className="btn-primary btn-sm"
            onClick={onStartInCwd}
            disabled={!canStart}
          >
            Start here
          </button>
        </div>
      </div>
      <button
        type="button"
        className="folder-current"
        onClick={onOpenFolderPicker}
        disabled={!canStart}
        title="Open folder picker"
      >
        <span className="folder-current-icon" aria-hidden="true">▤</span>
        <span className="folder-current-path">
          {canStart ? path : 'select an online host to pick a working directory'}
        </span>
        <span className="folder-current-hint">
          {canStart ? 'tap to browse' : ''}
        </span>
      </button>
    </section>
  );
}

function deriveSessionTitle(state) {
  const last = [...state.events].reverse().find((e) => e.role === 'user' && e.text);
  if (last?.text) {
    const trimmed = last.text.trim();
    return trimmed.length > 80 ? `${trimmed.slice(0, 77)}…` : trimmed;
  }
  if (state.session?.sessionId) {
    return `session ${state.session.sessionId.slice(0, 10)}…`;
  }
  return 'No active session';
}

function formatHostIdent(host, telemetry) {
  if (!host) return null;
  const cpu = telemetry?.current?.cpu;
  const cpuBit = cpu?.cores ? `${cpu.cores}c` : null;
  const gpu = telemetry?.current?.gpu?.name;
  return [host.platform || null, cpuBit, gpu]
    .filter(Boolean)
    .join(' · ');
}
