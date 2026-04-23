import { useState } from 'react';
import { useRemotex } from './hooks/useRemotex';
import { SCREENS } from './config';
import { Toast } from './components/Toast';
import { ApprovalDialog } from './components/ApprovalDialog';
import { SessionScreen } from './screens/SessionScreen';
import { SearchScreen } from './screens/SearchScreen';
import { StatusBadge } from './components/StatusBadge';
import { HostRow } from './components/HostRow';
import { ThreadRow } from './components/ThreadRow';
import { FileRow } from './components/FileRow';
import { joinPath, shortenCwd } from './util/path';

export default function App() {
  const r = useRemotex();
  const { state } = r;
  const [sidebarOpen, setSidebarOpen] = useState(false);

  function closeSidebar() {
    setSidebarOpen(false);
  }

  const selectedHost = state.hosts.find((h) => h.id === state.selectedHostId);
  const showSearch = state.screen === SCREENS.Search;
  const showSession =
    state.session || state.status !== 'idle' || state.events.length > 0;

  return (
    <div className={`app chat-layout ${sidebarOpen ? 'sidebar-open' : ''}`}>
      <div className="mobile-bar">
        <button
          type="button"
          className="menu-button"
          onClick={() => setSidebarOpen(true)}
          aria-label="Open sidebar"
        >
          ☰
        </button>
        <span className="brand">REMOTEX</span>
        <StatusBadge status={state.status} />
      </div>

      <button
        type="button"
        className="sidebar-scrim"
        onClick={closeSidebar}
        aria-label="Close sidebar"
      />

      <Sidebar
        state={state}
        selectedHost={selectedHost}
        onClose={closeSidebar}
        onTokenChange={r.setToken}
        onRefreshHosts={r.refreshHosts}
        onRefreshThreads={r.refreshThreads}
        onHostTap={(host) => {
          r.openHost(host);
          closeSidebar();
        }}
        onSearch={() => {
          r.goToSearch();
          closeSidebar();
        }}
        onNewSession={() => {
          r.openSession({});
          closeSidebar();
        }}
        onResumeThread={(thread) => {
          r.openSession({ threadId: thread.id });
          closeSidebar();
        }}
        onBrowseFiles={() => {
          r.goToFiles();
          setSidebarOpen(true);
        }}
        onNavigateFile={r.browseDir}
        onBrowseUp={r.browseUp}
        onStartHere={() => {
          r.startSessionInCurrentPath();
          closeSidebar();
        }}
      />

      <main className="chat-main">
        {state.screen === SCREENS.Search && (
          <SearchScreen
            state={state}
            onQueryChange={r.setSearchQuery}
            onSearch={(query, opts) => r.searchChats(query, opts || {})}
            onModeChange={r.setSearchMode}
            onRerankChange={r.setSearchRerank}
            onOpenResult={r.openSearchResult}
          />
        )}
        {!showSearch && showSession && (
          <SessionScreen
            state={state}
            onSend={r.sendTurn}
            onStop={r.interruptTurn}
            onModelChange={r.setModel}
            onEffortChange={r.setEffort}
            onPermissionsChange={r.setPermissions}
            onAttachImage={r.attachImage}
            onRemoveImage={r.removeImage}
          />
        )}
        {!showSearch && !showSession && (
          <EmptyChat
            selectedHost={selectedHost}
            canStart={!!selectedHost?.online}
            hasHosts={state.hosts.length > 0}
            onOpenSidebar={() => setSidebarOpen(true)}
            onNewSession={() => r.openSession({})}
          />
        )}
      </main>
      <ApprovalDialog prompt={state.pendingApproval} onDecision={r.resolveApproval} />
      <Toast message={state.error} tone="error" onDismiss={r.clearError} />
      <Toast message={state.slashFeedback} tone="info" onDismiss={r.clearFeedback} />
    </div>
  );
}

function Sidebar({
  state,
  selectedHost,
  onClose,
  onTokenChange,
  onRefreshHosts,
  onRefreshThreads,
  onHostTap,
  onSearch,
  onNewSession,
  onResumeThread,
  onBrowseFiles,
  onNavigateFile,
  onBrowseUp,
  onStartHere,
}) {
  const path = state.browsePath || '/';
  const selectedLabel = selectedHost?.nickname || state.selectedHostId || 'No host selected';
  const canStart = !!selectedHost?.online;
  const showFiles = state.screen === SCREENS.Files && canStart;

  return (
    <aside className="app-sidebar" aria-label="Remotex navigation">
      <div className="sidebar-head">
        <div>
          <div className="brand">REMOTEX</div>
          <div className="sidebar-subtitle">Codex sessions</div>
        </div>
        <button type="button" className="sidebar-close" onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>

      <div className="sidebar-scroll">
        <section className="sidebar-section sidebar-token">
          <label htmlFor="sidebar-token">User token</label>
          <input
            id="sidebar-token"
            type="text"
            value={state.userToken}
            onChange={(e) => onTokenChange(e.target.value)}
            spellCheck={false}
          />
        </section>

        <section className="sidebar-section">
          <div className="sidebar-action-grid">
            <button type="button" onClick={onRefreshHosts}>
              {state.hostsLoading ? 'Refreshing' : 'Refresh'}
            </button>
            <button type="button" onClick={onSearch}>
              Search
            </button>
          </div>
        </section>

        <section className="sidebar-section">
          <div className="sidebar-section-head">
            <span>Hosts</span>
            <span>{state.hosts.length}</span>
          </div>
          {state.hosts.length === 0 ? (
            <div className="sidebar-empty">
              {state.hostsLoading ? 'loading hosts...' : 'no hosts online yet'}
            </div>
          ) : (
            <div className="host-list compact-list">
              {state.hosts.map((host) => (
                <HostRow key={host.id} host={host} onClick={onHostTap} />
              ))}
            </div>
          )}
        </section>

        <section className="sidebar-section">
          <div className="sidebar-section-head">
            <span>{selectedLabel}</span>
            {selectedHost?.online && <span className="sidebar-online">online</span>}
          </div>
          <button
            type="button"
            className="sidebar-new-chat"
            onClick={onNewSession}
            disabled={!canStart}
          >
            <span className="plus-badge">+</span>
            <span>New chat</span>
          </button>
          <button
            type="button"
            className="sidebar-folder"
            onClick={onBrowseFiles}
            disabled={!canStart}
          >
            <span>Folder</span>
            <span>{state.browsePath ? shortenCwd(state.browsePath) : 'default cwd'}</span>
          </button>
        </section>

        {showFiles && (
          <section className="sidebar-section sidebar-files">
            <div className="sidebar-section-head">
              <span>Folder picker</span>
              <button
                type="button"
                className="sidebar-mini-button"
                onClick={onBrowseUp}
                disabled={path === '/'}
              >
                Up
              </button>
            </div>
            <div className="cwd-path sidebar-cwd">{path}</div>
            <button
              type="button"
              className="btn-primary"
              onClick={onStartHere}
              disabled={state.browseLoading}
            >
              Start here
            </button>
            {state.browseLoading && state.browseEntries.length === 0 ? (
              <div className="sidebar-empty">loading folder...</div>
            ) : state.browseEntries.length === 0 ? (
              <div className="sidebar-empty">empty folder</div>
            ) : (
              <div className="fs-list sidebar-fs-list">
                {state.browseEntries.map((entry) => (
                  <FileRow
                    key={entry.fileName}
                    entry={entry}
                    onOpenDir={() => onNavigateFile(joinPath(path, entry.fileName))}
                  />
                ))}
              </div>
            )}
          </section>
        )}

        <section className="sidebar-section sidebar-threads">
          <div className="sidebar-section-head">
            <span>Chats</span>
            <button
              type="button"
              className="sidebar-mini-button"
              onClick={() => onRefreshThreads()}
              disabled={!state.selectedHostId}
            >
              Refresh
            </button>
          </div>
          {state.threadsLoading && state.threads.length === 0 ? (
            <div className="sidebar-empty">loading chats...</div>
          ) : state.threads.length === 0 ? (
            <div className="sidebar-empty">
              {state.selectedHostId ? 'no previous chats' : 'select a host'}
            </div>
          ) : (
            <div className="thread-list compact-list">
              {state.threads.map((thread) => (
                <ThreadRow
                  key={thread.id}
                  thread={thread}
                  onClick={onResumeThread}
                />
              ))}
            </div>
          )}
        </section>
      </div>
    </aside>
  );
}

function EmptyChat({ selectedHost, canStart, hasHosts, onOpenSidebar, onNewSession }) {
  return (
    <div className="empty-chat">
      <div className="empty-chat-inner">
        <div className="empty-chat-mark">R</div>
        <h1>Start a Codex chat</h1>
        <p>
          {selectedHost
            ? `Ready on ${selectedHost.nickname}.`
            : hasHosts
            ? 'Pick a host from the sidebar, then start a chat.'
            : 'Refresh hosts in the sidebar to find an online daemon.'}
        </p>
        <div className="empty-chat-actions">
          <button type="button" className="btn-primary" onClick={onNewSession} disabled={!canStart}>
            New chat
          </button>
          <button type="button" className="btn-surface mobile-only" onClick={onOpenSidebar}>
            Open sidebar
          </button>
        </div>
      </div>
    </div>
  );
}
