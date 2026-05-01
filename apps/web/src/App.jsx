import { useState } from 'react';
import { useRemotex } from './hooks/useRemotex';
import { useBackgroundCompletionAlert } from './hooks/useBackgroundCompletionAlert';
import { SCREENS, STATUS } from './config';
import { Toast } from './components/Toast';
import { ApprovalDialog } from './components/ApprovalDialog';
import { SessionScreen } from './screens/SessionScreen';
import { SearchScreen } from './screens/SearchScreen';
import { DashboardScreen } from './screens/DashboardScreen';
import { DashboardHeader } from './components/DashboardHeader';
import { HostsSidebar } from './components/HostsSidebar';
import { TelemetrySidebar } from './components/TelemetrySidebar';
import { FolderPickerModal } from './components/FolderPickerModal';
import { shortenCwd } from './util/path';

export default function App() {
  const r = useRemotex();
  const { state } = r;
  // Tab-title + favicon flash for backgrounded users — only fires if the
  // tab was hidden at any point during the pending turn.
  useBackgroundCompletionAlert(state);
  const [leftOpen, setLeftOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false);
  const [folderPickerOpen, setFolderPickerOpen] = useState(false);

  const selectedHost = state.hosts.find((h) => h.id === state.selectedHostId);
  const telemetry = state.selectedHostId
    ? state.hostTelemetry[state.selectedHostId]
    : null;

  const closeDrawers = () => {
    setLeftOpen(false);
    setRightOpen(false);
  };

  const openSession = ({ threadId, cwd } = {}) => {
    r.openSession({ threadId, cwd });
    closeDrawers();
  };

  const isSessionActive = !!state.session || state.status !== STATUS.Idle;
  const onSessionScreen = state.screen === SCREENS.Session && isSessionActive;
  const onSearchScreen = state.screen === SCREENS.Search;
  const onFilesScreen = state.screen === SCREENS.Files;

  const layoutClass = [
    'app',
    'dashboard-layout',
    leftOpen ? 'left-open' : '',
    rightOpen ? 'right-open' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={layoutClass}>
      <DashboardHeader
        state={state}
        onMenuClick={() => setLeftOpen((v) => !v)}
        onToggleTelemetry={() => setRightOpen((v) => !v)}
        onDashboard={() => {
          r.goToDashboard();
          closeDrawers();
        }}
      />

      <button
        type="button"
        className="sidebar-scrim"
        onClick={closeDrawers}
        aria-label="Close drawers"
      />

      <HostsSidebar
        state={state}
        selectedHost={selectedHost}
        onClose={() => setLeftOpen(false)}
        onRefreshHosts={r.refreshHosts}
        onSelectHost={(host) => {
          r.openHost(host);
          closeDrawers();
        }}
        onNewSession={() => openSession({})}
        onResumeThread={(thread) => openSession({ threadId: thread.id })}
      />

      <main className="dashboard-main">
        {onSearchScreen ? (
          <SearchScreen
            state={state}
            onQueryChange={r.setSearchQuery}
            onSearch={(query, opts) => r.searchChats(query, opts || {})}
            onModeChange={r.setSearchMode}
            onRerankChange={r.setSearchRerank}
            onOpenResult={r.openSearchResult}
          />
        ) : onSessionScreen ? (
          <SessionScreen
            state={state}
            onSend={r.sendTurn}
            onStop={r.interruptTurn}
            onModelChange={r.setModel}
            onEffortChange={r.setEffort}
            onPermissionsChange={r.setPermissions}
            onAttachImage={r.attachImage}
            onRemoveImage={r.removeImage}
            workspaceApi={{
              apiRef: r.apiRef,
              upload: r.workspaceUploadFile,
            }}
          />
        ) : (
          <DashboardScreen
            state={state}
            selectedHost={selectedHost}
            telemetry={telemetry}
            onOpenSession={() => {
              if (isSessionActive) {
                r.goToSession();
              } else {
                openSession({});
              }
              closeDrawers();
            }}
            onEndSession={r.closeSession}
            onNewSession={() => openSession({})}
            onOpenSearch={r.goToSearch}
            onSearchChange={r.setSearchQuery}
            onRunSearch={(query) => {
              r.goToSearch();
              r.searchChats(query, { mode: state.searchMode, rerank: state.searchRerank });
            }}
            onBrowseFiles={() => {
              r.goToFiles();
              setLeftOpen(false);
            }}
            onOpenFolderPicker={() => setFolderPickerOpen(true)}
            onStartInCwd={() => openSession({ cwd: state.browsePath || null })}
            onRefreshThreads={() => r.refreshThreads()}
            onOpenManageHosts={() => setLeftOpen(true)}
          />
        )}

        {onFilesScreen && !onSessionScreen && !onSearchScreen && null}
      </main>

      <TelemetrySidebar
        telemetry={telemetry}
        selectedHost={selectedHost}
        onClose={() => setRightOpen(false)}
      />

      <footer className="dashboard-footer">
        <FooterStat label="Workspace" value={shortenCwd(state.browsePath || '~')} />
        <FooterStat label="Permissions" value={state.permissions} />
        <FooterStat
          label="Auto-save"
          value={<span className="footer-on">● on</span>}
        />
        <FooterStat label="Host" value={selectedHost?.nickname || '—'} />
        <FooterStat label="Terminal" value="zsh" />
        <FooterStat
          label="Last sync"
          value={formatSync(telemetry?.lastUpdate)}
        />
      </footer>

      <FolderPickerModal
        open={folderPickerOpen}
        initialPath={state.browsePath || '/'}
        onClose={() => setFolderPickerOpen(false)}
        onListDirectory={r.listDirectory}
        onCreateFolder={r.createFolder}
        onSelect={(p) => {
          setFolderPickerOpen(false);
          // Keep the dashboard badge in sync before opening the session.
          r.browseDir(p);
          openSession({ cwd: p });
        }}
      />

      <ApprovalDialog prompt={state.pendingApproval} onDecision={r.resolveApproval} />
      <Toast message={state.error} tone="error" onDismiss={r.clearError} />
      <Toast message={state.slashFeedback} tone="info" onDismiss={r.clearFeedback} />
    </div>
  );
}

function FooterStat({ label, value }) {
  return (
    <div className="footer-stat">
      <span className="footer-stat-label">{label}</span>
      <span className="footer-stat-value">{value}</span>
    </div>
  );
}

function formatSync(ts) {
  if (!ts) return '—';
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}
