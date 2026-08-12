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
import { SessionPane } from './components/SessionPane';
import { hostHomePath, hostDisplayName } from './util/host';
import { recordVisit } from './util/folderHistory';
import { clearToken } from './util/tokenStorage';
import {
  MAX_ON_SCREEN,
  focusTab,
  openTab,
  closeTab,
  threadTabTitle,
} from './util/sessionTabs';

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

  const closeRightView = useCallback(() => {
    setRightView('off');
    setRightOpen(false);
  }, [setRightView]);

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
    // Mirror guard: resuming a thread that's already open as a tab
    // focuses that pane instead of loading the same chat into the
    // primary session next to it.
    if (threadId && sessionTabs.some((t) => t.key === threadId)) {
      focusPane(threadId);
      closeDrawers();
      return;
    }
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

  // Desktop multi-session tabs. Extra sessions beyond the primary one,
  // VS Code style: every tab stays connected; at most MAX_ON_SCREEN
  // sessions (primary + extras) render as a grid, the rest are
  // background tabs that swap in on focus.
  const [sessionTabs, setSessionTabs] = useState([]);
  const [shownPanes, setShownPanes] = useState([]);

  const isSessionActive = !!state.session || state.status !== STATUS.Idle;
  const maxExtras = isSessionActive ? MAX_ON_SCREEN - 1 : MAX_ON_SCREEN;
  const visibleExtras = shownPanes.slice(0, maxExtras);

  const openThreadInTab = (thread) => {
    // Never the same chat twice on screen: if this thread IS the primary
    // session, just focus it instead of opening a duplicate pane.
    const primaryThreadId = state.session?.threadId || state.session?.thread_id;
    if (thread.id && thread.id === primaryThreadId) {
      r.goToSession();
      closeDrawers();
      return;
    }
    const next = openTab(
      sessionTabs,
      shownPanes,
      {
        key: thread.id,
        threadId: thread.id,
        hostId: thread.host_id || state.selectedHostId,
        cwd: thread.cwd || null,
        title: threadTabTitle(thread),
      },
      maxExtras,
    );
    setSessionTabs(next.tabs);
    setShownPanes(next.shown);
    r.goToSession();
    closeDrawers();
  };
  const focusPane = (key) => {
    setShownPanes(focusTab(shownPanes, key, maxExtras));
    r.goToSession();
  };
  const closePane = (key) => {
    const next = closeTab(sessionTabs, shownPanes, key);
    setSessionTabs(next.tabs);
    setShownPanes(next.shown);
  };

  const onSessionScreen = state.screen === SCREENS.Session && isSessionActive;
  // Tabs alone can hold the session screen open even with no primary
  // session (e.g. every chat was opened as a tab from the sidebar).
  const onSessionGrid =
    state.screen === SCREENS.Session && (isSessionActive || sessionTabs.length > 0);
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
        onOpenThreadInTab={openThreadInTab}
        onPrefetchThread={r.prefetchThreadPreview}
      />

      <main className={`dashboard-main ${onSessionGrid ? 'with-session-grid' : ''}`}>
        {sessionTabs.length > 0 && (
          <div className="session-tabstrip" role="tablist" aria-label="Open sessions">
            {isSessionActive && (
              <button
                type="button"
                role="tab"
                aria-selected={onSessionScreen}
                className={`session-tab primary ${onSessionScreen ? 'active' : ''}`}
                onClick={() => r.goToSession()}
                title={state.session?.cwd || 'session'}
              >
                {state.session?.cwd || 'session'}
              </button>
            )}
            {sessionTabs.map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={visibleExtras.includes(t.key)}
                className={`session-tab ${visibleExtras.includes(t.key) ? 'active' : ''}`}
                onClick={() => focusPane(t.key)}
                title={t.title}
              >
                <span className="session-tab-label">{t.cwd || t.title}</span>
                <span
                  className="session-tab-close"
                  role="button"
                  aria-label={`Close session tab ${t.title}`}
                  tabIndex={0}
                  onClick={(e) => {
                    e.stopPropagation();
                    closePane(t.key);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.stopPropagation();
                      closePane(t.key);
                    }
                  }}
                >
                  ✕
                </span>
              </button>
            ))}
          </div>
        )}
        <div
          className={`session-grid count-${(onSessionScreen ? 1 : 0) + visibleExtras.length}`}
          style={{ display: onSessionGrid ? undefined : 'none' }}
        >
          {onSessionScreen && (
            <div className="session-cell">
              <SessionScreen
                compact={visibleExtras.length > 0}
                state={state}
                onSend={r.sendTurn}
                onStop={r.interruptTurn}
                onSteer={r.steerTurn}
                onQueue={r.queueTurn}
                onRemoveQueued={r.removeQueuedTurn}
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
            </div>
          )}
          {/* Hidden tabs stay mounted so their sockets and transcripts
              survive being backgrounded, exactly like editor tabs. */}
          {sessionTabs.map((t) => (
            <div
              key={t.key}
              className="session-cell session-cell-extra"
              style={{ display: visibleExtras.includes(t.key) ? undefined : 'none' }}
            >
              <SessionPane
                token={auth.token}
                remember={auth.remember}
                hostId={t.hostId}
                threadId={t.threadId}
                cwd={t.cwd}
                title={t.title}
                onClose={() => closePane(t.key)}
              />
            </div>
          ))}
        </div>
        {onSessionGrid ? null : onFilesScreen ? (
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
        onClose={closeRightView}
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
