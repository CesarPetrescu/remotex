import { useCallback, useEffect, useState } from 'react';
import { useRemotex } from './hooks/useRemotex';
import { useBackgroundCompletionAlert } from './hooks/useBackgroundCompletionAlert';
import { SCREENS, STATUS } from './config';
import { Toast } from './components/Toast';
import { SessionScreen } from './screens/SessionScreen';
import { FilesScreen } from './screens/FilesScreen';
import { DashboardScreen } from './screens/DashboardScreen';
import { LoginScreen } from './screens/LoginScreen';
import { DashboardHeader } from './components/DashboardHeader';
import { HostsSidebar } from './components/HostsSidebar';
import { RightSidebar } from './components/RightSidebar';
import { JumpPicker } from './components/JumpPicker';
import { SettingsPanel } from './components/SettingsPanel';
import { hostHomePath, hostDisplayName } from './util/host';
import { recordVisit } from './util/folderHistory';
import { clearToken } from './util/tokenStorage';

const RIGHT_VIEWS = ['prompts', 'telemetry', 'off'];
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
  const [auth, setAuth] = useState(null);

  if (!auth) return <LoginScreen onAuthenticated={setAuth} />;

  return (
    <AuthenticatedApp
      auth={auth}
      onLogout={() => {
        clearToken();
        window.location.replace('/');
      }}
    />
  );
}

function AuthenticatedApp({ auth, onLogout }) {
  const r = useRemotex({
    token: auth.token,
    remember: auth.remember,
    initialHosts: auth.hosts,
  });
  const { state } = r;
  // Tab-title + favicon flash for backgrounded users — only fires if the
  // tab was hidden at any point during the pending turn.
  useBackgroundCompletionAlert(state);
  const [leftOpen, setLeftOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false);
  const [jumpOpen, setJumpOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [jumpMode, setJumpMode] = useState('search');
  // Right sidebar: prompts | telemetry | off. Goal lives in the
  // composer now, so stale persisted "goal" values fall back to off.
  const [rightView, setRightViewState] = useState(() =>
    readPersisted(RIGHT_VIEW_KEY, RIGHT_VIEWS, 'off'),
  );
  const setRightView = useCallback((v) => {
    writePersisted(RIGHT_VIEW_KEY, v);
    setRightViewState(v);
  }, []);
  const pendingPromptKey =
    state.pendingUserInput?.callId || state.pendingApproval?.approvalId || null;
  // Every unanswered prompt counts, not just the two on screen — the
  // rest are queued behind them (contract F).
  const pendingPromptCount =
    state.pendingApprovals.length + state.pendingUserInputs.length;
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

  useEffect(() => {
    if (!pendingPromptKey) {
      if (rightView === 'prompts') {
        setRightView('off');
        setRightOpen(false);
      }
      return;
    }
    setRightView('prompts');
    if (!isCompactLayout()) {
      setRightOpen(true);
    }
  }, [pendingPromptKey, rightView, setRightView]);

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

  const openSession = ({ threadId, cwd, hostId } = {}) => {
    if (cwd) recordVisit(hostId || state.selectedHostId, cwd);
    r.openSession({ threadId, cwd, hostId });
    closeDrawers();
  };

  // The Jump picker is the single entry point for choosing a cwd. `mode`
  // controls whether it opens in fuzzy-recall (search) or tree (browse).
  const openJump = (mode = 'search') => {
    if (!selectedHost?.online) return;
    setJumpMode(mode);
    setJumpOpen(true);
    closeDrawers();
  };

  const openNewSessionBrowser = () => openJump('search');

  const isSessionActive = !!state.session || state.status !== STATUS.Idle;
  const onSessionScreen = state.screen === SCREENS.Session && isSessionActive;
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
        api={r.apiRef.current}
        onMenuClick={() => setLeftOpen((v) => !v)}
        rightView={rightView}
        onRightView={openRightView}
        leftCollapsed={leftCollapsed}
        onToggleLeftCollapsed={() => setLeftCollapsed(!leftCollapsed)}
        hasPendingPrompt={hasPendingPrompt}
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
        onNewSession={openNewSessionBrowser}
        onOpenSettings={() => setSettingsOpen(true)}
        onResumeThread={(thread) => openSession({
          hostId: thread.host_id,
          threadId: thread.id,
          cwd: thread.cwd || null,
        })}
        onPrefetchThread={r.prefetchThreadPreview}
      />

      <main className="dashboard-main">
        {onSessionScreen ? (
          <SessionScreen
            state={state}
            onSend={r.sendTurn}
            onStop={r.interruptTurn}
            onSteer={r.steerTurn}
            onLoadOlder={r.loadOlderHistory}
            onModelChange={r.setModel}
            onEffortChange={r.setEffort}
            onPermissionsChange={r.setPermissions}
            onAttachImage={r.attachImage}
            onRemoveImage={r.removeImage}
            workspaceApi={{
              apiRef: r.apiRef,
              upload: r.workspaceUploadFile,
              sendSlash: r.sendSlash,
            }}
          />
        ) : onFilesScreen ? (
          <FilesScreen
            state={state}
            onNavigate={r.browseDir}
            onUp={r.browseUp}
            onStartHere={() => openSession({ cwd: state.browsePath || hostHomePath(selectedHost) })}
          />
        ) : (
            <DashboardScreen
              state={state}
              selectedHost={selectedHost}
              onOpenSession={() => {
              if (isSessionActive) {
                r.goToSession();
              } else {
                openSession({});
              }
              closeDrawers();
            }}
            onEndSession={r.closeSession}
            onBrowseFiles={() => openJump('browse')}
            onOpenFolderPicker={() => openJump('search')}
            onStartInCwd={() => openSession({ cwd: state.browsePath || null })}
            onResumeThread={(thread) => openSession({
              hostId: thread.host_id,
              threadId: thread.id,
              cwd: thread.cwd || null,
            })}
            onPrefetchThread={r.prefetchThreadPreview}
            onRefreshThreads={() => r.refreshThreads()}
            onOpenManageHosts={() => setLeftOpen(true)}
          />
        )}
      </main>

      <RightSidebar
        view={rightView}
        telemetry={telemetry}
        selectedHost={selectedHost}
        pendingApproval={state.pendingApproval}
        pendingUserInput={state.pendingUserInput}
        pendingApprovalCount={state.pendingApprovals.length}
        pendingUserInputCount={state.pendingUserInputs.length}
        onResolveApproval={r.resolveApproval}
        onResolveUserInput={r.resolveUserInput}
        onCancelUserInput={r.cancelUserInput}
      />

      <JumpPicker
        open={jumpOpen}
        onClose={() => setJumpOpen(false)}
        hostId={state.selectedHostId}
        hostHome={hostHomePath(selectedHost)}
        hostName={selectedHost ? hostDisplayName(selectedHost) : ''}
        initialPath={state.browsePath || hostHomePath(selectedHost)}
        initialMode={jumpMode}
        onListDirectory={r.listDirectory}
        onCreateFolder={r.createFolder}
        onSelect={(p) => {
          setJumpOpen(false);
          openSession({ cwd: p });
        }}
      />

      <SettingsPanel
        open={settingsOpen}
        remember={state.rememberToken}
        onSignOut={onLogout}
        onClose={() => setSettingsOpen(false)}
      />

      <Toast message={state.error} tone="error" onDismiss={r.clearError} />
      <Toast message={state.slashFeedback} tone="info" onDismiss={r.clearFeedback} />
    </div>
  );
}
