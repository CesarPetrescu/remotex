import { useCallback, useEffect, useState } from 'react';
import { useRemotex } from './hooks/useRemotex';
import { useBackgroundCompletionAlert } from './hooks/useBackgroundCompletionAlert';
import { SCREENS, STATUS } from './config';
import { Toast } from './components/Toast';
import { SessionScreen } from './screens/SessionScreen';
import { SearchScreen } from './screens/SearchScreen';
import { DashboardScreen } from './screens/DashboardScreen';
import { DashboardHeader } from './components/DashboardHeader';
import { HostsSidebar } from './components/HostsSidebar';
import { RightSidebar } from './components/RightSidebar';
import { FolderPickerModal } from './components/FolderPickerModal';
import { OrchestratorLauncher } from './components/OrchestratorLauncher';
import { shortenCwd } from './util/path';

const RIGHT_VIEWS = ['plan', 'telemetry', 'off'];
const RIGHT_VIEW_KEY = 'remotex.rightView';
const LEFT_COLLAPSED_KEY = 'remotex.leftCollapsed';

function readPersisted(key, allowed, fallback) {
  try {
    const v = localStorage.getItem(key);
    if (allowed && !allowed.includes(v)) return fallback;
    return v ?? fallback;
  } catch {
    return fallback;
  }
}
function writePersisted(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* ignore */
  }
}

function isCompactLayout() {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia('(max-width: 1000px)').matches
  );
}

export default function App() {
  const r = useRemotex();
  const { state } = r;
  // Tab-title + favicon flash for backgrounded users — only fires if the
  // tab was hidden at any point during the pending turn.
  useBackgroundCompletionAlert(state);
  const [leftOpen, setLeftOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false);
  const [folderPickerOpen, setFolderPickerOpen] = useState(false);
  const [orchLauncherOpen, setOrchLauncherOpen] = useState(false);
  // Right sidebar: tabs PLAN | TELEMETRY | off. Persists across
  // reloads so the user's last choice sticks.
  const [rightView, setRightViewState] = useState(() =>
    readPersisted(RIGHT_VIEW_KEY, RIGHT_VIEWS, 'telemetry'),
  );
  const setRightView = useCallback((v) => {
    writePersisted(RIGHT_VIEW_KEY, v);
    setRightViewState(v);
  }, []);
  const pendingPromptKey =
    state.pendingUserInput?.callId || state.pendingApproval?.approvalId || null;
  const pendingPromptCount = (state.pendingUserInput ? 1 : 0) + (state.pendingApproval ? 1 : 0);
  const hasPendingPrompt = pendingPromptCount > 0;

  const openRightView = useCallback((v) => {
    setRightView(v);
    if (v === 'off') {
      setRightOpen(false);
      return;
    }
    if (isCompactLayout()) {
      setRightOpen(true);
    }
  }, [setRightView]);

  const closeRightView = useCallback(() => {
    setRightView('off');
    setRightOpen(false);
  }, [setRightView]);

  const toggleRightDrawer = useCallback(() => {
    if (rightOpen) {
      setRightOpen(false);
      return;
    }
    if (rightView === 'off') {
      setRightView(hasPendingPrompt ? 'plan' : 'telemetry');
    }
    setRightOpen(true);
  }, [hasPendingPrompt, rightOpen, rightView, setRightView]);

  useEffect(() => {
    if (!pendingPromptKey) return;
    setRightView('plan');
    if (!isCompactLayout()) {
      setRightOpen(true);
    }
  }, [pendingPromptKey, setRightView]);

  // Left hosts sidebar: collapsed mode (desktop). The mobile drawer
  // is controlled by leftOpen above; this is the desktop on/off.
  const [leftCollapsed, setLeftCollapsedState] = useState(() =>
    readPersisted(LEFT_COLLAPSED_KEY, ['true', 'false'], 'false') === 'true',
  );
  const setLeftCollapsed = (v) => {
    writePersisted(LEFT_COLLAPSED_KEY, String(v));
    setLeftCollapsedState(v);
  };

  const selectedHost = state.hosts.find((h) => h.id === state.selectedHostId);
  const telemetry = state.selectedHostId
    ? state.hostTelemetry[state.selectedHostId]
    : null;

  const closeDrawers = () => {
    setLeftOpen(false);
    setRightOpen(false);
  };

  // "+ New session" always opens a coder. Orchestrator is started
  // explicitly from the dashboard's Orchestrate tile or the in-session
  // chip's "switch to orchestrator" affordance — that way there's no
  // sticky preferredKind state silently routing future "+ New" taps
  // to the orchestrator just because the user once tapped it.
  const openSession = ({ threadId, cwd, hostId } = {}) => {
    r.openSession({ threadId, cwd, hostId });
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
    leftCollapsed ? 'left-collapsed' : '',
    rightView === 'off' ? 'right-off' : '',
    `right-view-${rightView}`,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={layoutClass}>
      <DashboardHeader
        state={state}
        onMenuClick={() => setLeftOpen((v) => !v)}
        onToggleTelemetry={toggleRightDrawer}
        rightView={rightView}
        onRightView={openRightView}
        leftCollapsed={leftCollapsed}
        onToggleLeftCollapsed={() => setLeftCollapsed(!leftCollapsed)}
        hasOrchestrator={!!state.orchestrator?.active || hasPendingPrompt}
        pendingPromptCount={pendingPromptCount}
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
        onResumeThread={(thread) => openSession({
          hostId: thread.host_id,
          threadId: thread.id,
          cwd: thread.cwd || null,
        })}
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
              sendSlash: r.sendSlash,
              // In-session chip: "switch to orchestrator" opens the
              // launcher; "switch to coder" opens the folder picker
              // → that picks goes through openSession() above which
              // is now always a coder. Both paths spawn a NEW
              // session; the current one keeps running until the
              // user navigates away.
              openOrchestrator: () => setOrchLauncherOpen(true),
              openCoder: () => setFolderPickerOpen(true),
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
            onOpenOrchestrator={() => setOrchLauncherOpen(true)}
          />
        )}

        {onFilesScreen && !onSessionScreen && !onSearchScreen && null}
      </main>

      <RightSidebar
        view={rightView}
        onView={openRightView}
        onClose={closeRightView}
        telemetry={telemetry}
        selectedHost={selectedHost}
        orchestrator={state.orchestrator}
        hasOrchestrator={!!state.orchestrator?.active}
        pendingApproval={state.pendingApproval}
        pendingUserInput={state.pendingUserInput}
        onResolveApproval={r.resolveApproval}
        onResolveUserInput={r.resolveUserInput}
        onCancelUserInput={r.cancelUserInput}
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

      <Toast message={state.error} tone="error" onDismiss={r.clearError} />
      <Toast message={state.slashFeedback} tone="info" onDismiss={r.clearFeedback} />

      <OrchestratorLauncher
        open={orchLauncherOpen}
        defaults={{
          model: state.model,
          effort: state.effort,
          permissions: state.permissions,
        }}
        models={state.modelOptions}
        onCancel={() => setOrchLauncherOpen(false)}
        onLaunch={(opts) => {
          setOrchLauncherOpen(false);
          r.openOrchestratorSession({
            ...opts,
            cwd: state.browsePath || null,
          });
          closeDrawers();
        }}
      />
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
