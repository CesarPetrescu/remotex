import { shortenCwd } from '../util/path';
import { relativeAge } from '../util/time';
import { STATUS } from '../config';
import { usePrefetchIntent } from '../hooks/usePrefetchIntent';

// Middle-column view when no session screen is active.
//
// One primary action. With no session: a hero card that picks a folder and
// starts (the old ACTIVE SESSION / QUICK ACTIONS / WORKSPACE cards were
// three skins over this same action). With a session: a compact card that
// reopens or ends it. Below either: recent sessions, promoted from the
// sidebar into the main column — they're what returning users actually want.

export function DashboardScreen({
  state,
  selectedHost,
  onOpenSession,
  onEndSession,
  onBrowseFiles,
  onOpenFolderPicker,
  onStartInCwd,
  onResumeThread,
  onPrefetchThread,
  onRefreshThreads,
  onOpenManageHosts,
}) {
  const hasSession = !!state.session && state.status !== STATUS.Idle;
  const online = !!selectedHost?.online;
  return (
    <div className="dashboard-center">
      {hasSession ? (
        <ActiveSessionCard
          state={state}
          selectedHost={selectedHost}
          onOpenSession={onOpenSession}
          onEndSession={onEndSession}
        />
      ) : (
        <StartCard
          state={state}
          selectedHost={selectedHost}
          online={online}
          onOpenFolderPicker={onOpenFolderPicker}
          onStartInCwd={onStartInCwd}
          onBrowseFiles={onBrowseFiles}
          onOpenManageHosts={onOpenManageHosts}
        />
      )}
      <RecentSessions
        threads={state.threads}
        loading={state.threadsLoading}
        activeThreadId={state.session?.threadId}
        canResume={online}
        onResume={onResumeThread}
        onPrefetch={onPrefetchThread}
        onRefresh={onRefreshThreads}
      />
    </div>
  );
}

function StartCard({
  state,
  selectedHost,
  online,
  onOpenFolderPicker,
  onStartInCwd,
  onBrowseFiles,
  onOpenManageHosts,
}) {
  const path = state.browsePath || selectedHost?.home_dir || '/';
  return (
    <section className="card card-hero">
      <div className="card-head card-head-split">
        <span className="card-eyebrow">New session</span>
        {selectedHost && (
          <span className="hero-host">
            <span className={`dot ${online ? 'ok' : ''}`} />
            {selectedHost.nickname}
          </span>
        )}
      </div>
      <button
        type="button"
        className="folder-current"
        onClick={onOpenFolderPicker}
        disabled={!online}
        title="Choose a working directory"
      >
        <span className="folder-current-icon" aria-hidden="true">▤</span>
        <span className="folder-current-path">
          {online ? shortenCwd(path) : 'select an online host to start'}
        </span>
        {online && <span className="folder-current-hint">change</span>}
      </button>
      <div className="card-actions">
        <button
          type="button"
          className="btn-primary btn-lg"
          onClick={onStartInCwd}
          disabled={!online}
        >
          Start session
        </button>
        <button
          type="button"
          className="btn-surface"
          onClick={onOpenFolderPicker}
          disabled={!online}
        >
          Choose folder…
        </button>
      </div>
      <div className="hero-links">
        <button type="button" className="link-button" onClick={onBrowseFiles} disabled={!online}>
          Browse files
        </button>
        <span className="hero-links-sep" aria-hidden="true">·</span>
        <button type="button" className="link-button" onClick={onOpenManageHosts}>
          Manage hosts
        </button>
      </div>
    </section>
  );
}

function ActiveSessionCard({ state, selectedHost, onOpenSession, onEndSession }) {
  const info = state.session;
  const statusLabel = {
    [STATUS.Opening]: 'Opening',
    [STATUS.Connecting]: 'Connecting',
    [STATUS.Connected]: 'Connected',
    [STATUS.Disconnected]: 'Disconnected',
    [STATUS.Error]: 'Error',
  }[state.status] || 'Connected';

  return (
    <section className="card card-hero card-active-session">
      <div className="card-head">
        <span className="card-eyebrow">Active session</span>
        <span className="card-dot ok" />
      </div>
      <h2 className="card-title">{deriveSessionTitle(state)}</h2>
      <div className="session-facts">
        {selectedHost && (
          <SessionFact
            label="Host"
            value={
              <span className="session-fact-value">
                <span className={`dot ${selectedHost.online ? 'ok' : ''}`} />
                <span>{selectedHost.nickname}</span>
              </span>
            }
          />
        )}
        {info?.cwd && <SessionFact label="CWD" value={shortenCwd(info.cwd)} mono />}
        <SessionFact label="Model" value={info?.model || state.model || 'default'} mono />
        <SessionFact label="State" value={statusLabel} tone="live" />
      </div>
      <div className="card-actions">
        <button type="button" className="btn-primary btn-lg" onClick={onOpenSession}>
          Open session
        </button>
        <button
          type="button"
          className="btn-surface"
          onClick={() => {
            // Confirm before killing an in-flight turn — costly to re-do.
            if (state.pending) {
              const ok = window.confirm(
                'A turn is currently running. End the session anyway?\n' +
                'Use Cancel to keep it running in the background.',
              );
              if (!ok) return;
            }
            onEndSession();
          }}
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

function RecentSessions({
  threads,
  loading,
  activeThreadId,
  canResume,
  onResume,
  onPrefetch,
  onRefresh,
}) {
  return (
    <section className="card card-recent">
      <div className="card-head">
        <span className="card-eyebrow">Recent sessions</span>
        <div className="card-head-actions">
          <button
            type="button"
            className="btn-surface btn-sm"
            onClick={onRefresh}
            disabled={!canResume}
            aria-label="Refresh recent sessions"
            title="Refresh"
          >
            ↻
          </button>
        </div>
      </div>
      {threads.length === 0 ? (
        <p className="recent-empty">
          {loading
            ? 'Loading saved sessions…'
            : canResume
              ? 'No saved sessions on this host yet.'
              : 'Select an online host to see its saved sessions.'}
        </p>
      ) : (
        <div className="recent-grid">
          {threads.map((thread) => (
            <RecentRow
              key={thread.id}
              thread={thread}
              active={thread.id === activeThreadId}
              disabled={!canResume}
              onClick={() => onResume(thread)}
              onPrefetch={onPrefetch}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function RecentRow({ thread, active, disabled, onClick, onPrefetch }) {
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  const title = hasSpecificTitle ? thread.title : (thread.preview || '(no preview)');
  const age = relativeAge(thread.updated_at ?? thread.created_at);
  const intent = usePrefetchIntent(() => onPrefetch?.(thread));
  return (
    <button
      type="button"
      className={`recent-row ${active ? 'active' : ''}`}
      onClick={onClick}
      disabled={disabled}
      {...intent}
    >
      <span className="recent-row-title">{title}</span>
      <span className="recent-row-meta">
        <span>{age}</span>
        {thread.cwd && <span className="recent-row-cwd">{shortenCwd(thread.cwd)}</span>}
      </span>
    </button>
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
  return 'Session in progress';
}
