// Top-level hook that owns all web-client state. Equivalent to
// Android's RemotexViewModel — host list, thread list, file browser,
// and the session machine all live here, plus navigation. Kept as one
// reducer so transitions are easy to reason about, mirroring the
// Kotlin ViewModel one-state-object shape.

import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';
import { RelayClient } from '../api/relayClient';
import { SessionSocket } from '../api/sessionSocket';
import { FALLBACK_MODEL_OPTIONS, SCREENS, STATUS, effortsFor } from '../config';
import { parseSlash } from '../util/slash';
import { parentPath } from '../util/path';
import { buildUrl, parseUrl } from '../util/url';

const TOKEN_KEY = 'remotex.userToken';
const PROMPT_BACKUP_PREFIX = 'remotex.pendingPrompts.';
const SUBAGENT_ACTIVITY_MAX = 24;
const loadToken = () => {
  try {
    return localStorage.getItem(TOKEN_KEY) || 'demo-user-token';
  } catch {
    return 'demo-user-token';
  }
};

function promptBackupKey(sessionId) {
  return `${PROMPT_BACKUP_PREFIX}${sessionId}`;
}

function readPromptBackup(sessionId) {
  if (!sessionId) return null;
  try {
    const raw = sessionStorage.getItem(promptBackupKey(sessionId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writePromptBackup(sessionId, patch) {
  if (!sessionId) return;
  try {
    const prev = readPromptBackup(sessionId) || {};
    const next = {
      approval: 'approval' in patch ? patch.approval : prev.approval || null,
      userInput: 'userInput' in patch ? patch.userInput : prev.userInput || null,
    };
    if (!next.approval && !next.userInput) {
      sessionStorage.removeItem(promptBackupKey(sessionId));
      return;
    }
    sessionStorage.setItem(promptBackupKey(sessionId), JSON.stringify(next));
  } catch {
    // ignore private-mode/storage failures
  }
}

function clearPromptBackup(sessionId) {
  if (!sessionId) return;
  try {
    sessionStorage.removeItem(promptBackupKey(sessionId));
  } catch {
    // ignore
  }
}

function normalizeApprovalPrompt(data = {}) {
  if (!data.approval_id) return null;
  return {
    approvalId: data.approval_id,
    kind: data.kind,
    reason: data.reason,
    command: data.command,
    cwd: data.cwd,
    permissions: data.permissions,
    decisions: data.decisions || ['accept', 'decline'],
  };
}

function normalizeUserInputPrompt(data = {}) {
  if (!data.call_id) return null;
  return {
    callId: data.call_id,
    turnId: data.turn_id,
    questions: Array.isArray(data.questions) ? data.questions : [],
  };
}

function normalizePromptSnapshot(frame = {}) {
  const approvalData = Array.isArray(frame.approvals) ? frame.approvals[0] : null;
  const inputData = Array.isArray(frame.user_inputs) ? frame.user_inputs[0] : null;
  return {
    approval: normalizeApprovalPrompt(approvalData || {}),
    userInput: normalizeUserInputPrompt(inputData || {}),
  };
}

const initialState = {
  screen: SCREENS.Hosts,
  userToken: loadToken(),
  hosts: [],
  hostsLoading: false,
  selectedHostId: null,
  threads: [],
  threadsHostId: null,
  threadsLoading: false,
  searchQuery: '',
  searchResults: [],
  searchLoading: false,
  searchConfig: null,
  searchMode: 'hybrid',
  searchSignals: [],
  searchRerank: 'auto',
  searchReranked: false,
  // searchStages tracks each step in the pipeline for live progress:
  //   { name, status: 'pending'|'running'|'done'|'skipped', elapsed_ms, count }
  searchStages: [],
  searchStageOrigin: null, // which stage produced the current results list
  browsePath: '',
  browseEntries: [],
  browseLoading: false,
  status: STATUS.Idle,
  session: null,
  events: [],
  pending: false,
  model: '',
  effort: 'medium',
  permissions: 'default',
  pendingImages: [],
  pendingApproval: null,
  slashFeedback: null,
  planMode: false,
  // Preferred kind for the next "+ New session" tap. The Composer's
  // 4th chip surfaces this; persisted to localStorage so it sticks.
  preferredKind: (() => {
    try { return localStorage.getItem('remotex.preferredKind') || 'coder'; } catch { return 'coder'; }
  })(),
  // True between thread-status:resuming and thread-status:resumed/resume-failed.
  // Codex can take a minute+ to re-hydrate big rollouts; surfaced as a banner.
  resuming: false,
  resumingSinceMs: 0,
  // Cumulative codex token totals for the current session. Updated when
  // the daemon forwards thread/tokenUsage/updated; reset on SESSION_RESET.
  tokensInput: 0,
  tokensOutput: 0,
  tokensCached: 0,
  tokensReasoning: 0,
  error: null,
  // hostTelemetry[hostId] = {
  //   current: { cpu:{percent,cores,temp_c}, memory:{used_bytes,total_bytes,percent},
  //              gpu:{name,percent,mem_used_mb,mem_total_mb,temp_c}|null,
  //              network:{up_bps,down_bps}, uptime_s, load_avg:[1m,5m,15m], ts },
  //   history: { cpu:[], mem:[], gpu:[], up:[], down:[] },  // rolling, newest last
  //   lastUpdate: epoch_ms
  // }
  hostTelemetry: {},
  // Model picker list is fetched from the relay's /api/models endpoint
  // on first paint and replaces this fallback. See services/relay/models.py
  // for the canonical source of truth.
  modelOptions: FALLBACK_MODEL_OPTIONS,
  // Orchestrator session shape: {
  //   active: bool,            // true when the active session is kind=orchestrator
  //   steps: [{ step_id, title, deps, status, summary, child_session_id, ... }],
  //   brainSubagents: [{ id, tool, status, depth, ... }],
  //   agents: { [agent_id]: { id, label, step_id, thread_id, events: [] } },
  //   finished: { ok, summary?, error? } | null,
  // }
  orchestrator: { active: false, steps: [], finished: null, brainSubagents: [], agents: {} },
};

const TELEMETRY_HISTORY_MAX = 60;

// --- reducer ---

function reducer(state, action) {
  switch (action.type) {
    case 'SET_TOKEN':
      return { ...state, userToken: action.token };
    case 'SET_SCREEN':
      return { ...state, screen: action.screen };
    case 'SET_ERROR':
      return { ...state, error: action.error };
    case 'CLEAR_FEEDBACK':
      return { ...state, slashFeedback: null };
    case 'SET_PREFERRED_KIND':
      try { localStorage.setItem('remotex.preferredKind', action.kind); } catch { /* private mode */ }
      return { ...state, preferredKind: action.kind };

    case 'HOSTS':
      return { ...state, hosts: action.hosts, hostsLoading: false };
    case 'HOSTS_LOADING':
      return { ...state, hostsLoading: action.loading };

    case 'SELECT_HOST':
      return {
        ...state,
        selectedHostId: action.id,
        threads: action.id === state.threadsHostId ? state.threads : [],
        threadsHostId: action.id === state.threadsHostId ? state.threadsHostId : null,
        threadsLoading: false,
      };
    case 'THREADS':
      if (action.hostId && action.hostId !== state.selectedHostId) return state;
      return {
        ...state,
        threads: action.threads,
        threadsHostId: action.hostId || state.selectedHostId,
        threadsLoading: false,
      };
    case 'THREADS_LOADING':
      if (action.hostId && action.hostId !== state.selectedHostId) return state;
      return { ...state, threadsLoading: action.loading };

    case 'SEARCH_QUERY':
      return { ...state, searchQuery: action.query };
    case 'SEARCH_CONFIG':
      return { ...state, searchConfig: action.config };
    case 'SEARCH_LOADING':
      return { ...state, searchLoading: action.loading };
    case 'SEARCH_RESULTS':
      return {
        ...state,
        searchResults: action.results !== undefined ? action.results : state.searchResults,
        searchLoading: false,
        searchQuery: action.query ?? state.searchQuery,
        searchMode: action.mode ?? state.searchMode,
        searchSignals: action.signals ?? state.searchSignals,
        searchReranked: action.reranked ?? state.searchReranked,
        searchStageOrigin: action.origin ?? state.searchStageOrigin,
      };
    case 'SET_SEARCH_SIGNALS':
      return { ...state, searchSignals: action.signals };
    case 'SEARCH_MODE':
      return { ...state, searchMode: action.mode };
    case 'SEARCH_RERANK':
      return { ...state, searchRerank: action.rerank };
    case 'SEARCH_PLAN': {
      const stages = action.stages.map((name) => ({
        name,
        status: 'pending',
        elapsed_ms: null,
        count: null,
      }));
      return {
        ...state,
        searchStages: stages,
        searchResults: [],
        searchSignals: [],
        searchReranked: false,
        searchStageOrigin: null,
        searchLoading: true,
      };
    }
    case 'SEARCH_STAGE_UPDATE': {
      const stages = state.searchStages.map((stage) =>
        stage.name === action.name ? { ...stage, ...action.patch } : stage,
      );
      return { ...state, searchStages: stages };
    }
    case 'SEARCH_STAGE_RESULTS':
      return {
        ...state,
        searchResults: action.results,
        searchStageOrigin: action.origin,
      };

    case 'BROWSE_LOADING':
      return {
        ...state,
        browseLoading: action.loading,
        browsePath: action.path ?? state.browsePath,
      };
    case 'BROWSE':
      return {
        ...state,
        browsePath: action.path,
        browseEntries: action.entries,
        browseLoading: false,
      };

    case 'SESSION_RESET':
      return {
        ...state,
        events: [],
        session: null,
        pendingApproval: null,
        pendingUserInput: null,
        slashFeedback: null,
        pendingImages: [],
        pending: false,
        planMode: false,
        resuming: false,
        resumingSinceMs: 0,
        tokensInput: 0,
        tokensOutput: 0,
        tokensCached: 0,
        tokensReasoning: 0,
        status: action.status ?? STATUS.Idle,
        orchestrator: { active: false, steps: [], finished: null, brainSubagents: [], agents: {} },
      };
    case 'ORCHESTRATOR_BEGIN':
      return {
        ...state,
        orchestrator: { active: true, steps: [], finished: null, brainSubagents: [], agents: {} },
      };
    case 'ORCHESTRATOR_PLAN': {
      const existingById = new Map(
        (state.orchestrator.steps || []).map((step) => [step.step_id, step]),
      );
      const steps = (action.steps || []).map((step) => {
        const prev = existingById.get(step.step_id);
        return prev?.live ? { ...step, live: prev.live } : step;
      });
      return {
        ...state,
        orchestrator: {
          ...state.orchestrator,
          active: true,
          steps,
        },
      };
    }
    case 'ORCHESTRATOR_STEP_STATUS': {
      const incoming = action.step;
      if (!incoming?.step_id) return state;
      const existing = state.orchestrator.steps;
      const idx = existing.findIndex((s) => s.step_id === incoming.step_id);
      let nextSteps;
      if (idx >= 0) {
        nextSteps = existing.slice();
        nextSteps[idx] = { ...existing[idx], ...incoming };
      } else {
        nextSteps = [...existing, incoming];
      }
      return {
        ...state,
        orchestrator: { ...state.orchestrator, active: true, steps: nextSteps },
      };
    }
    case 'ORCHESTRATOR_STEP_LIVE': {
      // Live progress for one child step: appends streaming text or
      // sets the current "what's running" label. Cap accumulated text
      // so a chatty child doesn't bloat the React state.
      const { step_id, patch } = action;
      if (!step_id || !patch) return state;
      const existing = state.orchestrator.steps;
      const idx = existing.findIndex((s) => s.step_id === step_id);
      if (idx < 0) return state;
      const prev = existing[idx];
      const prevLive = prev.live || { text: '', label: null, item_id: null, subagents: [] };
      const nextLive = { ...prevLive };
      // Item boundary: a new item_id wipes the running text buffer so
      // we don't bleed one step's reasoning into the next message.
      if (patch.item_id && patch.item_id !== prevLive.item_id) {
        nextLive.text = '';
      }
      if (patch.reset_text) nextLive.text = '';
      if (patch.delta) {
        nextLive.text = (nextLive.text || '') + patch.delta;
        if (nextLive.text.length > 800) {
          nextLive.text = '…' + nextLive.text.slice(-800);
        }
      }
      if ('label' in patch) nextLive.label = patch.label;
      if ('item_id' in patch) nextLive.item_id = patch.item_id;
      if ('item_type' in patch) nextLive.item_type = patch.item_type;
      if ('completed' in patch) nextLive.completed = patch.completed;
      if (patch.subagentEvent) {
        nextLive.subagents = upsertSubagentEvent(nextLive.subagents, patch.subagentEvent);
      }
      const nextSteps = existing.slice();
      nextSteps[idx] = { ...prev, live: nextLive };
      return {
        ...state,
        orchestrator: { ...state.orchestrator, active: true, steps: nextSteps },
      };
    }
    case 'ORCHESTRATOR_BRAIN_SUBAGENT':
      return {
        ...state,
        orchestrator: {
          ...state.orchestrator,
          active: true,
          brainSubagents: upsertSubagentEvent(
            state.orchestrator.brainSubagents,
            action.event,
          ),
        },
      };
    case 'ORCHESTRATOR_AGENT_EVENT':
      return {
        ...state,
        orchestrator: {
          ...state.orchestrator,
          active: true,
          agents: upsertAgentTranscript(
            state.orchestrator.agents,
            action.payload,
          ),
        },
      };
    case 'ORCHESTRATOR_FINISHED':
      return {
        ...state,
        orchestrator: {
          ...state.orchestrator,
          active: true,
          finished: action.payload,
        },
      };
    case 'TOKEN_USAGE':
      return {
        ...state,
        tokensInput:     action.input     ?? state.tokensInput,
        tokensOutput:    action.output    ?? state.tokensOutput,
        tokensCached:    action.cached    ?? state.tokensCached,
        tokensReasoning: action.reasoning ?? state.tokensReasoning,
      };
    case 'RESUMING_START':
      return { ...state, resuming: true, resumingSinceMs: action.sinceMs };
    case 'RESUMING_END':
      return { ...state, resuming: false, resumingSinceMs: 0 };
    case 'SESSION_ATTACHED':
      return { ...state, session: action.session, status: STATUS.Connecting };
    case 'SESSION_INFO':
      return { ...state, session: { ...(state.session || {}), ...action.info } };
    case 'SESSION_STATUS':
      return { ...state, status: action.status };

    case 'APPEND_EVENT':
      if (action.event?.id && state.events.some((e) => e.id === action.event.id)) {
        return state;
      }
      return { ...state, events: [...state.events, action.event] };
    case 'APPEND_DELTA':
      return {
        ...state,
        events: state.events.map((e) => {
          if (e.id !== action.id) return e;
          if (e.role === 'tool') {
            return { ...e, output: (e.output || '') + action.delta };
          }
          return { ...e, text: (e.text || '') + action.delta };
        }),
      };
    case 'COMPLETE_EVENT':
      return {
        ...state,
        events: state.events.map((e) =>
          e.id === action.id ? { ...e, ...action.patch, completed: true } : e,
        ),
      };

    case 'PENDING':
      return { ...state, pending: action.pending };

    case 'SET_MODEL':
      return {
        ...state,
        model: action.model,
        effort: effortsFor(action.model, state.modelOptions).includes(state.effort)
          ? state.effort
          : '',
      };
    case 'SET_EFFORT':
      return { ...state, effort: action.effort };
    case 'SET_PERMS':
      return { ...state, permissions: action.permissions };

    case 'ATTACH_IMAGE':
      return { ...state, pendingImages: [...state.pendingImages, action.image] };
    case 'REMOVE_IMAGE':
      return {
        ...state,
        pendingImages: state.pendingImages.filter((_, i) => i !== action.index),
      };
    case 'CLEAR_IMAGES':
      return { ...state, pendingImages: [] };

    case 'APPROVAL_REQUEST':
      return { ...state, pendingApproval: action.prompt };
    case 'APPROVAL_CLEAR':
      return { ...state, pendingApproval: null };

    case 'USER_INPUT_REQUEST':
      return { ...state, pendingUserInput: action.prompt };
    case 'USER_INPUT_CLEAR':
      return { ...state, pendingUserInput: null };
    case 'PENDING_PROMPTS':
      return {
        ...state,
        pendingApproval: action.approval || null,
        pendingUserInput: action.userInput || null,
      };

    case 'SLASH_FEEDBACK':
      return { ...state, slashFeedback: action.text };
    case 'SET_PLAN':
      return { ...state, planMode: action.on };

    case 'MODEL_OPTIONS':
      return { ...state, modelOptions: action.options };

    case 'TELEMETRY': {
      const hostId = action.hostId;
      if (!hostId || !action.data) return state;
      const prev = state.hostTelemetry[hostId];
      const prevHistory = prev?.history || { cpu: [], mem: [], gpu: [], up: [], down: [] };
      const d = action.data;
      const push = (arr, v) => {
        const next = arr.length >= TELEMETRY_HISTORY_MAX ? arr.slice(1) : arr.slice();
        next.push(Number.isFinite(v) ? v : 0);
        return next;
      };
      const history = {
        cpu: push(prevHistory.cpu, d.cpu?.percent ?? 0),
        mem: push(prevHistory.mem, d.memory?.percent ?? 0),
        gpu: push(prevHistory.gpu, d.gpu?.percent ?? 0),
        up: push(prevHistory.up, d.network?.up_bps ?? 0),
        down: push(prevHistory.down, d.network?.down_bps ?? 0),
      };
      return {
        ...state,
        hostTelemetry: {
          ...state.hostTelemetry,
          [hostId]: { current: d, history, lastUpdate: Date.now() },
        },
      };
    }

    default:
      return state;
  }
}

// --- hook ---

export function useRemotex() {
  const [state, dispatch] = useReducer(reducer, initialState);

  // Mutable plumbing — reducer state is derived output, these are the
  // I/O handles that outlive renders.
  const apiRef = useRef(new RelayClient(state.userToken));
  const socketRef = useRef(null);
  const reconnectRef = useRef(null);
  const reconnectAttemptRef = useRef(0);
  const userClosedRef = useRef(false);
  // Latest sendTurn inputs (model/effort/perms/images) — read lazily so
  // sendTurn doesn't invalidate on every picker tweak.
  const latestInputsRef = useRef({});
  latestInputsRef.current = {
    model: state.model,
    effort: state.effort,
    permissions: state.permissions,
    pendingImages: state.pendingImages,
    pendingApproval: state.pendingApproval,
    pendingUserInput: state.pendingUserInput,
    browsePath: state.browsePath,
    selectedHostId: state.selectedHostId,
    userToken: state.userToken,
    sessionId: state.session?.sessionId,
  };

  useEffect(() => {
    try {
      localStorage.setItem(TOKEN_KEY, state.userToken);
    } catch {
      // ignore
    }
    apiRef.current.setToken(state.userToken);
  }, [state.userToken]);

  // --- helpers ---

  const closeSession = useCallback(() => {
    userClosedRef.current = true;
    clearPromptBackup(latestInputsRef.current.sessionId);
    if (reconnectRef.current) {
      clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
    }
    if (socketRef.current) {
      socketRef.current.close({ endSession: true });
      socketRef.current = null;
    }
    dispatch({ type: 'SESSION_RESET', status: STATUS.Idle });
  }, []);

  const detachSession = useCallback((status = STATUS.Idle) => {
    userClosedRef.current = true;
    if (reconnectRef.current) {
      clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
    }
    reconnectAttemptRef.current = 0;
    if (socketRef.current) {
      const sock = socketRef.current;
      socketRef.current = null;
      sock.close();
    }
    dispatch({ type: 'SESSION_RESET', status });
  }, []);

  const hydrateOrchestratorPlan = useCallback((sid) => {
    if (!sid) return;
    apiRef.current
      .getOrchestratorPlan(sid)
      .then((snapshot) => {
        if (snapshot?.kind !== 'orchestrator') return;
        dispatch({ type: 'ORCHESTRATOR_PLAN', steps: snapshot.steps || [] });
        dispatch({ type: 'SESSION_INFO', info: { kind: 'orchestrator' } });
      })
      .catch(() => {
        // Non-orchestrator sessions return 400 here; ignore.
      });
  }, []);

  const handleFrame = useCallback((frame) => {
    if (frame.type === 'attached') {
      reconnectAttemptRef.current = 0;
      dispatch({
        type: 'SESSION_STATUS',
        status: Number(frame.replay_from || 0) > 0 ? STATUS.Connected : STATUS.Connecting,
      });
      dispatch({ type: 'SET_ERROR', error: null });
      dispatch({
        type: 'SESSION_INFO',
        info: {
          sessionId: frame.session_id,
          hostId: frame.host_id,
          clientId: frame.client_id,
          peerCount: frame.peer_count,
        },
      });
      hydrateOrchestratorPlan(frame.session_id);
      const backup = readPromptBackup(frame.session_id);
      if (backup?.approval || backup?.userInput) {
        dispatch({
          type: 'PENDING_PROMPTS',
          approval: backup.approval || null,
          userInput: backup.userInput || null,
        });
      }
      return;
    }
    if (frame.type === 'pending-prompts') {
      const prompts = normalizePromptSnapshot(frame);
      if (prompts.approval || prompts.userInput) {
        writePromptBackup(frame.session_id, prompts);
      } else {
        clearPromptBackup(frame.session_id);
      }
      dispatch({ type: 'PENDING_PROMPTS', ...prompts });
      return;
    }
    if (frame.type === 'approval-resolved') {
      const pending = latestInputsRef.current.pendingApproval;
      if (!frame.approval_id || pending?.approvalId === frame.approval_id) {
        writePromptBackup(frame.session_id || latestInputsRef.current.sessionId, { approval: null });
        dispatch({ type: 'APPROVAL_CLEAR' });
      }
      return;
    }
    if (frame.type === 'user-input-resolved') {
      const pending = latestInputsRef.current.pendingUserInput;
      if (!frame.call_id || pending?.callId === frame.call_id) {
        writePromptBackup(frame.session_id || latestInputsRef.current.sessionId, { userInput: null });
        dispatch({ type: 'USER_INPUT_CLEAR' });
      }
      return;
    }
    if (frame.type === 'session-closed') {
      clearPromptBackup(frame.session_id || latestInputsRef.current.sessionId);
      dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
      dispatch({ type: 'PENDING', pending: false });
      return;
    }
    if (frame.type === 'error') {
      dispatch({ type: 'SET_ERROR', error: frame.error || 'relay error' });
      return;
    }
    if (frame.type === 'host-telemetry') {
      if (frame.host_id && frame.data) {
        dispatch({ type: 'TELEMETRY', hostId: frame.host_id, data: frame.data });
      }
      return;
    }
    if (frame.type !== 'session-event') return;

    const ev = frame.event || {};
    const data = ev.data || {};
    switch (ev.kind) {
      case 'session-started': {
        const transport = data.transport || 'stdio';
        const resuming = data.resuming === true;
        const readOnlyHistory = transport === 'history';
        dispatch({
          type: 'SESSION_STATUS',
          status: readOnlyHistory
            ? STATUS.Error
            : resuming
              ? STATUS.Connecting
              : STATUS.Connected,
        });
        dispatch({
          type: 'SESSION_INFO',
          // Spread `kind` only when present — older daemon builds
          // don't include it and we don't want to overwrite an
          // optimistic `kind: 'orchestrator'` set in
          // openOrchestratorSession with `undefined`.
          info: data.kind
            ? { model: data.model, cwd: data.cwd, kind: data.kind }
            : { model: data.model, cwd: data.cwd },
        });
        dispatch({
          type: 'SET_ERROR',
          error: readOnlyHistory
            ? 'Saved chat is history-only. Start a new session to continue.'
            : resuming
              ? 'Resuming saved chat…'
              : null,
        });
        return;
      }
      case 'item-started': {
        const ev = buildItemEvent(data);
        if (ev) dispatch({ type: 'APPEND_EVENT', event: ev });
        if (data.item_type === 'collab_agent_tool_call' && data._orchestrator_role === 'brain') {
          dispatch({
            type: 'ORCHESTRATOR_BRAIN_SUBAGENT',
            event: normalizeSubagentEvent({ ...data, kind: 'item-started' }),
          });
        }
        if (data.item_type === 'user_message' && !data.replayed) {
          dispatch({ type: 'PENDING', pending: true });
        }
        return;
      }
      case 'item-delta':
        if (data.delta) {
          dispatch({ type: 'APPEND_DELTA', id: data.item_id, delta: data.delta });
        }
        return;
      case 'item-completed': {
        const patch = {};
        if (data.item_type === 'agent_message' || data.item_type === 'agent_reasoning') {
          if (data.text) patch.text = data.text;
        } else if (data.item_type === 'tool_call') {
          if (data.output) patch.output = data.output;
        } else if (data.item_type === 'mcp_tool_call') {
          patch.output = formatMcpOutput(data);
          patch.status = data.status || '';
          patch.durationMs = data.duration_ms;
          patch.error = data.error || '';
          patch.rawResult = data.result;
        } else if (data.item_type === 'dynamic_tool_call') {
          patch.output = formatDynamicOutput(data);
          patch.status = data.status || '';
          patch.durationMs = data.duration_ms;
          patch.error = data.error || '';
          patch.rawResult = data.content_items;
        } else if (data.item_type === 'collab_agent_tool_call') {
          patch.output = formatCollabStatus(data);
        }
        dispatch({ type: 'COMPLETE_EVENT', id: data.item_id, patch });
        if (data.item_type === 'collab_agent_tool_call' && data._orchestrator_role === 'brain') {
          dispatch({
            type: 'ORCHESTRATOR_BRAIN_SUBAGENT',
            event: normalizeSubagentEvent({ ...data, kind: 'item-completed' }),
          });
        }
        return;
      }
      case 'turn-completed':
        clearPromptBackup(latestInputsRef.current.sessionId);
        dispatch({ type: 'PENDING_PROMPTS', approval: null, userInput: null });
        dispatch({ type: 'PENDING', pending: false });
        if (data.error) dispatch({ type: 'SET_ERROR', error: data.error });
        return;
      case 'approval-request':
        {
          const prompt = normalizeApprovalPrompt(data);
          if (!prompt) return;
          writePromptBackup(latestInputsRef.current.sessionId, { approval: prompt });
          dispatch({ type: 'APPROVAL_REQUEST', prompt });
        }
        return;
      case 'user-input-request':
        {
          const prompt = normalizeUserInputPrompt(data);
          if (!prompt) return;
          writePromptBackup(latestInputsRef.current.sessionId, { userInput: prompt });
          dispatch({ type: 'USER_INPUT_REQUEST', prompt });
        }
        return;
      case 'slash-ack': {
        const cmd = data.command || '?';
        const ok = data.ok === true;
        if (ok && cmd === 'plan') dispatch({ type: 'SET_PLAN', on: true });
        if (ok && cmd === 'default') dispatch({ type: 'SET_PLAN', on: false });
        const text = data.message
          ? `/${cmd} — ${data.message}`
          : !ok
          ? `/${cmd} failed: ${data.error || 'unknown error'}`
          : `/${cmd} ok`;
        dispatch({ type: 'SLASH_FEEDBACK', text });
        return;
      }
      case 'collab-modes': {
        const names = (data.modes || []).map((m) => m.name).filter(Boolean);
        dispatch({
          type: 'SLASH_FEEDBACK',
          text: `collab modes: ${names.join(', ')}`,
        });
        return;
      }
      case 'thread-status':
        if (data.status === 'resuming') {
          dispatch({ type: 'RESUMING_START', sinceMs: Date.now() });
        } else if (data.status === 'resumed') {
          dispatch({ type: 'RESUMING_END' });
          dispatch({ type: 'SESSION_STATUS', status: STATUS.Connected });
          dispatch({
            type: 'SESSION_INFO',
            info: { model: data.model, cwd: data.cwd },
          });
          dispatch({ type: 'SET_ERROR', error: null });
        } else if (data.status === 'resume-failed') {
          dispatch({ type: 'RESUMING_END' });
          dispatch({ type: 'SESSION_STATUS', status: STATUS.Error });
          dispatch({
            type: 'SET_ERROR',
            error: data.error || 'Saved chat could not be resumed.',
          });
          dispatch({ type: 'PENDING', pending: false });
        }
        return;
      case 'token-usage': {
        // Daemon already flattened codex's nested payload; just pull the
        // top-level counters and dispatch. Null leaves the prior value alone.
        const num = (k) => {
          const v = data[k];
          if (typeof v === 'number') return v;
          if (typeof v === 'string' && /^\d+$/.test(v)) return parseInt(v, 10);
          return null;
        };
        dispatch({
          type: 'TOKEN_USAGE',
          input:     num('input'),
          output:    num('output'),
          cached:    num('cached_input'),
          reasoning: num('reasoning_output'),
        });
        return;
      }
      case 'turn-started':
        dispatch({ type: 'PENDING', pending: true });
        return;
      case 'history-begin':
      case 'history-end':
        return;
      case 'orchestrator-plan':
        dispatch({ type: 'ORCHESTRATOR_PLAN', steps: data.steps || [] });
        return;
      case 'orchestrator-step-status':
        dispatch({ type: 'ORCHESTRATOR_STEP_STATUS', step: data });
        return;
      case 'orchestrator-agent-event':
        dispatch({ type: 'ORCHESTRATOR_AGENT_EVENT', payload: data });
        return;
      case 'orchestrator-step-event': {
        // Per-step live progress — text deltas, current tool/file
        // label, and completion markers. The reducer accumulates a
        // bounded `live.text` per step so the UI can show what the
        // child is doing right now without re-rendering the brain's
        // own event stream.
        const stepId = data.step_id;
        if (!stepId) return;
        const kind = data.kind;
        const itemType = data.item_type;
        const patch = {};
        if (kind === 'item-delta') {
          patch.item_id = data.item_id;
          patch.item_type = itemType;
          patch.delta = data.delta || '';
          // A new item replaces the previous live text. Detect via
          // item_id boundary: if the daemon sent a different item_id,
          // start fresh.
          patch._maybeReset = true;
        } else if (kind === 'item-started') {
          patch.item_id = data.item_id;
          patch.item_type = itemType;
          patch.completed = false;
          patch.reset_text = true;
          if (data.label) patch.label = data.label;
          else if (itemType === 'agent_message' || itemType === 'agent_reasoning') {
            patch.label = itemType === 'agent_reasoning' ? 'thinking…' : 'replying…';
          } else if (itemType) {
            patch.label = itemType.replace(/_/g, ' ');
          }
          if (itemType === 'collab_agent_tool_call') {
            patch.subagentEvent = normalizeSubagentEvent(data);
          }
        } else if (kind === 'item-completed') {
          patch.item_id = data.item_id;
          patch.item_type = itemType;
          patch.completed = true;
          if (data.label) patch.label = data.label;
          if (data.text) patch.delta = ''; // text already streamed
          if (itemType === 'collab_agent_tool_call') {
            patch.subagentEvent = normalizeSubagentEvent(data);
          }
        } else if (kind === 'turn-started') {
          patch.label = 'starting…';
          patch.reset_text = true;
        } else {
          return;
        }
        delete patch._maybeReset;
        dispatch({ type: 'ORCHESTRATOR_STEP_LIVE', step_id: stepId, patch });
        return;
      }
      case 'orchestrator-finished':
        dispatch({
          type: 'ORCHESTRATOR_FINISHED',
          payload: {
            ok: data.ok !== false,
            summary: data.summary || null,
            error: data.error || null,
          },
        });
        dispatch({ type: 'PENDING', pending: false });
        return;
      default:
        return;
    }
  }, [hydrateOrchestratorPlan]);

  // attachSocket + scheduleReconnect form a cycle (attach emits
  // disconnect → schedule → reattach). Use refs to break the cycle so
  // each callback is stable and neither needs the other in its deps.
  const attachSocketRef = useRef(null);
  const scheduleReconnectRef = useRef(null);

  const attachSocket = useCallback(
    (sid, { replayFromStart = false } = {}) => {
      const userToken = latestInputsRef.current.userToken;
      const previous = socketRef.current;
      if (previous) {
        socketRef.current = null;
        previous.close();
      }
      const sock = new SessionSocket({
        userToken,
        sessionId: sid,
        onStatus: (s) => {
          if (socketRef.current !== sock) return;
          if (s === 'connecting') {
            dispatch({ type: 'SESSION_STATUS', status: STATUS.Connecting });
          }
          if (s === 'disconnected' || s === 'error') {
            if (userClosedRef.current) {
              dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
              return;
            }
            dispatch({ type: 'PENDING', pending: false });
            dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
            scheduleReconnectRef.current?.(sid);
          }
        },
        onFrame: handleFrame,
        lastSeq: replayFromStart ? 0 : null,
      });
      socketRef.current = sock;
    },
    [handleFrame],
  );
  attachSocketRef.current = attachSocket;

  const scheduleReconnect = useCallback((sid) => {
    if (reconnectRef.current) clearTimeout(reconnectRef.current);
    const attempt = reconnectAttemptRef.current;
    const offline = typeof navigator !== 'undefined' && navigator.onLine === false;
    const base = offline ? 5000 : Math.min(30000, 1000 * 2 ** Math.min(attempt, 5));
    const jitter = Math.floor(Math.random() * Math.min(1000, base * 0.25));
    const delay = base + jitter;
    reconnectAttemptRef.current = attempt + 1;
    const label = offline
      ? 'waiting for network…'
      : `reconnecting in ${Math.ceil(delay / 1000)}s`;
    dispatch({ type: 'SET_ERROR', error: label });
    reconnectRef.current = setTimeout(() => {
      reconnectRef.current = null;
      if (userClosedRef.current) return;
      if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        scheduleReconnectRef.current?.(sid);
        return;
      }
      dispatch({ type: 'SESSION_STATUS', status: STATUS.Connecting });
      dispatch({ type: 'SET_ERROR', error: 'reconnecting…' });
      attachSocketRef.current?.(sid);
    }, delay);
  }, []);
  scheduleReconnectRef.current = scheduleReconnect;

  useEffect(() => {
    const reconnectActiveSession = () => {
      const sid = latestInputsRef.current.sessionId || socketRef.current?.sessionId;
      if (!sid || userClosedRef.current) return;
      if (state.status === STATUS.Connected && socketRef.current?.isOpen()) return;
      if (reconnectRef.current) clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
      reconnectAttemptRef.current = 0;
      attachSocketRef.current?.(sid);
    };
    const onOnline = () => reconnectActiveSession();
    const onVisible = () => {
      if (document.visibilityState === 'visible') reconnectActiveSession();
    };
    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [state.status]);

  // --- navigation ---

  const goToHosts = useCallback(() => {
    detachSession();
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
  }, [detachSession]);

  // Navigate back to the dashboard view WITHOUT killing the active
  // session. The relay-side keep-alive means the turn keeps running in
  // the background, and a later tap on the same chat reattaches via
  // the session-reuse path.
  const goToDashboard = useCallback(() => {
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
  }, []);

  const goToThreads = useCallback(() => {
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Threads });
  }, []);

  const goToSession = useCallback(() => {
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Session });
  }, []);

  const goToSearch = useCallback(async () => {
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Search });
    try {
      const config = await apiRef.current.searchConfig();
      dispatch({ type: 'SEARCH_CONFIG', config });
    } catch (t) {
      dispatch({ type: 'SET_ERROR', error: t.message });
    }
  }, []);

  const refreshHosts = useCallback(async () => {
    dispatch({ type: 'HOSTS_LOADING', loading: true });
    try {
      const hosts = await apiRef.current.listHosts();
      dispatch({ type: 'HOSTS', hosts });
    } catch (t) {
      dispatch({ type: 'HOSTS_LOADING', loading: false });
      dispatch({ type: 'SET_ERROR', error: t.message });
    }
  }, []);

  useEffect(() => {
    refreshHosts();
  }, [refreshHosts]);

  // Fetch the canonical model list from the relay once on mount. The
  // fallback constant in config.js stays in place if the fetch fails so
  // the picker still renders something the user can pick from.
  useEffect(() => {
    let cancelled = false;
    apiRef.current
      .listModels()
      .then((models) => {
        if (!cancelled && Array.isArray(models) && models.length > 0) {
          dispatch({ type: 'MODEL_OPTIONS', options: models });
        }
      })
      .catch(() => {
        // Silent: fallback list already in state.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Poll telemetry for whichever host is currently selected / online.
  // Push updates arrive over the session WS when a session is open; the
  // poll keeps the sidebar populated when it isn't and back-fills the
  // first sample immediately after selecting a host.
  useEffect(() => {
    const hostId = state.selectedHostId;
    if (!hostId) return undefined;
    const host = state.hosts.find((h) => h.id === hostId);
    if (!host?.online) return undefined;
    let cancelled = false;
    const tick = async () => {
      try {
        const snap = await apiRef.current.getHostTelemetry(hostId);
        if (cancelled) return;
        if (snap?.data) {
          dispatch({ type: 'TELEMETRY', hostId, data: snap.data });
        }
      } catch {
        // Transient fetch failures are benign — next tick will retry.
      }
    };
    tick();
    const h = setInterval(tick, 3000);
    return () => {
      cancelled = true;
      clearInterval(h);
    };
  }, [state.selectedHostId, state.hosts]);

  const refreshThreads = useCallback(
    async (hostOverride) => {
      const target = hostOverride || latestInputsRef.current.selectedHostId;
      if (!target) return;
      dispatch({ type: 'THREADS_LOADING', hostId: target, loading: true });
      try {
        const threads = await apiRef.current.listThreads(target, 25);
        dispatch({
          type: 'THREADS',
          hostId: target,
          threads: threads.map((thread) => ({ ...thread, host_id: target })),
        });
      } catch (t) {
        if (latestInputsRef.current.selectedHostId === target) {
          dispatch({ type: 'THREADS_LOADING', hostId: target, loading: false });
          dispatch({ type: 'SET_ERROR', error: t.message });
        }
      }
    },
    [],
  );

  const openHost = useCallback(
    (host) => {
      if (!host.online) {
        dispatch({ type: 'SET_ERROR', error: `${host.nickname} is offline` });
        return;
      }
      dispatch({ type: 'SELECT_HOST', id: host.id });
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Threads });
      refreshThreads(host.id);
    },
    [refreshThreads],
  );

  // Auto-select the first online host once the list loads so the
  // telemetry sidebar + dashboard have data without an extra click.
  // Declared after refreshThreads to stay out of its TDZ.
  useEffect(() => {
    if (state.selectedHostId) return;
    const firstOnline = state.hosts.find((h) => h.online);
    if (firstOnline) {
      dispatch({ type: 'SELECT_HOST', id: firstOnline.id });
      refreshThreads(firstOnline.id);
    }
  }, [state.hosts, state.selectedHostId, refreshThreads]);

  const browseDir = useCallback(async (path) => {
    const target = latestInputsRef.current.selectedHostId;
    if (!target) return;
    dispatch({ type: 'BROWSE_LOADING', loading: true, path });
    try {
      const r = await apiRef.current.readDirectory(target, path);
      const entries = r.entries.slice().sort((a, b) => {
        if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
        return a.fileName.localeCompare(b.fileName, undefined, { sensitivity: 'base' });
      });
      dispatch({ type: 'BROWSE', path: r.path, entries });
    } catch (t) {
      dispatch({ type: 'BROWSE_LOADING', loading: false });
      dispatch({ type: 'SET_ERROR', error: t.message });
    }
  }, []);

  const goToFiles = useCallback(
    (initialPath) => {
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Files });
      const start = initialPath || latestInputsRef.current.browsePath || '/';
      browseDir(start);
    },
    [browseDir],
  );

  const browseUp = useCallback(() => {
    const p = latestInputsRef.current.browsePath || '/';
    if (p === '/') return;
    browseDir(parentPath(p));
  }, [browseDir]);

  const createFolder = useCallback(
    async (parent, name) => {
      const target = latestInputsRef.current.selectedHostId;
      if (!target) throw new Error('no host selected');
      await apiRef.current.mkdir(target, parent, name);
      await browseDir(parent);
    },
    [browseDir],
  );

  // Lightweight directory listing that returns the payload directly without
  // touching the dashboard's browse state. The folder-picker modal uses this
  // so it can navigate independently of the tile grid below it.
  const listDirectory = useCallback(async (path) => {
    const target = latestInputsRef.current.selectedHostId;
    if (!target) throw new Error('no host selected');
    return apiRef.current.readDirectory(target, path);
  }, []);

  // --- workspace files (in-chat panel: read/rename/delete/upload) ---
  const workspaceListDirectory = useCallback(async (hostId, path) => {
    return apiRef.current.readDirectory(hostId, path);
  }, []);
  const workspaceReadFile = useCallback(async (hostId, path) => {
    return apiRef.current.readFile(hostId, path);
  }, []);
  const workspaceDeleteFile = useCallback(async (hostId, path) => {
    return apiRef.current.deleteFile(hostId, path);
  }, []);
  const workspaceRenameFile = useCallback(async (hostId, from, to) => {
    return apiRef.current.renameFile(hostId, from, to);
  }, []);
  const workspaceUploadFile = useCallback(async (hostId, dir, file) => {
    return apiRef.current.uploadFile(hostId, dir, file);
  }, []);

  // --- session ---

  const openSession = useCallback(
    async ({ threadId = null, cwd = null, hostId: hostOverride = null } = {}) => {
      const hostId = hostOverride || latestInputsRef.current.selectedHostId;
      if (!hostId) return;
      detachSession(STATUS.Opening);
      userClosedRef.current = false;
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Session });
      try {
        const sid = await apiRef.current.openSession(hostId, { threadId, cwd });
        dispatch({
          type: 'SESSION_ATTACHED',
          session: { sessionId: sid, hostId, cwd: cwd || null },
        });
        attachSocket(sid);
      } catch (t) {
        dispatch({ type: 'SESSION_STATUS', status: STATUS.Error });
        dispatch({ type: 'SET_ERROR', error: t.message });
      }
    },
    [attachSocket, detachSession],
  );

  // Orchestrator entrypoint. The relay opens a kind=orchestrator
  // session, the daemon spawns the brain Codex with the orchestration
  // system prompt, and child Codex sessions are spawned per planned
  // step. The user supplies a free-text task plus the policies that
  // every child inherits — model, effort, sandbox/approval permissions.
  const openOrchestratorSession = useCallback(
    async ({
      task,
      cwd = null,
      hostId: hostOverride = null,
      model = null,
      effort = null,
      permissions = null,
      approvalPolicy = null,
    }) => {
      const hostId = hostOverride || latestInputsRef.current.selectedHostId;
      if (!hostId) return;
      const cleaned = (task || '').trim();
      if (!cleaned) {
        dispatch({ type: 'SET_ERROR', error: 'orchestrator: task is required' });
        return;
      }
      detachSession(STATUS.Opening);
      userClosedRef.current = false;
      dispatch({ type: 'ORCHESTRATOR_BEGIN' });
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Session });
      try {
        const sid = await apiRef.current.openSession(hostId, {
          cwd,
          kind: 'orchestrator',
          task: cleaned,
          model: model || latestInputsRef.current.model,
          effort: effort || latestInputsRef.current.effort,
          permissions: permissions || latestInputsRef.current.permissions,
          approvalPolicy,
        });
        dispatch({
          type: 'SESSION_ATTACHED',
          session: { sessionId: sid, hostId, cwd: cwd || null, kind: 'orchestrator' },
        });
        attachSocket(sid);
      } catch (t) {
        dispatch({ type: 'SESSION_STATUS', status: STATUS.Error });
        dispatch({ type: 'SET_ERROR', error: t.message });
      }
    },
    [attachSocket, detachSession],
  );

  const searchAbortRef = useRef(null);
  const searchChats = useCallback(async (query, { mode = 'hybrid', rerank = 'auto' } = {}) => {
    const cleaned = (query || '').trim();
    dispatch({ type: 'SEARCH_QUERY', query });
    if (!cleaned) {
      dispatch({ type: 'SEARCH_RESULTS', results: [], query, signals: [], reranked: false, origin: null });
      return;
    }

    // Cancel any in-flight search before starting a new one.
    searchAbortRef.current?.();
    searchAbortRef.current = null;

    const { promise, abort } = apiRef.current.searchChatsStream(cleaned, {
      limit: 20,
      mode,
      rerank,
      onEvent: (ev) => {
        if (ev.type === 'plan') {
          dispatch({ type: 'SEARCH_PLAN', stages: ev.stages });
          return;
        }
        if (ev.type === 'signal') {
          dispatch({
            type: 'SEARCH_STAGE_UPDATE',
            name: ev.name,
            patch: { status: 'done', elapsed_ms: ev.elapsed_ms, count: ev.count },
          });
          // Promote this signal's results to the UI if nothing better has
          // landed yet. Priority: rerank > fused > any single signal.
          const origin = ev.name;
          dispatch({ type: 'SEARCH_STAGE_RESULTS', results: ev.results || [], origin });
          return;
        }
        if (ev.type === 'fused') {
          dispatch({
            type: 'SEARCH_STAGE_RESULTS',
            results: ev.results || [],
            origin: 'fused',
          });
          dispatch({ type: 'SET_SEARCH_SIGNALS', signals: ev.signals || [] });
          return;
        }
        if (ev.type === 'rerank_start') {
          dispatch({
            type: 'SEARCH_STAGE_UPDATE',
            name: 'rerank',
            patch: { status: 'running', count: ev.candidates },
          });
          return;
        }
        if (ev.type === 'rerank') {
          dispatch({
            type: 'SEARCH_STAGE_UPDATE',
            name: 'rerank',
            patch: { status: 'done', elapsed_ms: ev.elapsed_ms },
          });
          dispatch({
            type: 'SEARCH_STAGE_RESULTS',
            results: ev.results || [],
            origin: 'rerank',
          });
          return;
        }
        if (ev.type === 'rerank_error') {
          dispatch({
            type: 'SEARCH_STAGE_UPDATE',
            name: 'rerank',
            patch: { status: 'error', error: ev.message },
          });
          return;
        }
        if (ev.type === 'done') {
          dispatch({
            type: 'SEARCH_RESULTS',
            results: undefined, // preserve whatever stage-results left behind
            query: cleaned,
            mode: ev.mode,
            signals: ev.signals || [],
            reranked: !!ev.reranked,
            origin: ev.reranked ? 'rerank' : 'fused',
          });
          return;
        }
        if (ev.type === 'error') {
          dispatch({ type: 'SET_ERROR', error: ev.message });
        }
      },
    });
    searchAbortRef.current = abort;
    try {
      await promise;
    } catch (t) {
      if (t.name !== 'AbortError') {
        dispatch({ type: 'SET_ERROR', error: t.message });
      }
    } finally {
      dispatch({ type: 'SEARCH_LOADING', loading: false });
      if (searchAbortRef.current === abort) searchAbortRef.current = null;
    }
  }, []);

  const openSearchResult = useCallback(
    (result) => {
      if (!result?.thread_id) {
        dispatch({
          type: 'SET_ERROR',
          error: 'This result has no resumable Codex thread yet.',
        });
        return;
      }
      dispatch({ type: 'SELECT_HOST', id: result.host_id });
      openSession({
        hostId: result.host_id,
        threadId: result.thread_id,
        cwd: result.cwd || null,
      });
    },
    [openSession],
  );

  // Composer's slash autocomplete fires this when the user picks a
  // command. Slash commands never carry images so we bypass sendTurn.
  const sendSlash = useCallback((cmd, args = '') => {
    const sock = socketRef.current;
    if (!sock) return false;
    if (!sock.sendSlash(cmd, args)) {
      dispatch({ type: 'SET_ERROR', error: 'socket is not connected' });
      return false;
    }
    if (cmd === 'plan') dispatch({ type: 'SET_PLAN', on: true });
    if (cmd === 'default') dispatch({ type: 'SET_PLAN', on: false });
    return true;
  }, []);

  const sendTurn = useCallback(
    (rawText) => {
      const input = (rawText || '').trim();
      const { pendingImages, model, effort, permissions } = latestInputsRef.current;
      if (!input && pendingImages.length === 0) return;
      const sock = socketRef.current;
      if (!sock) return;

      if (pendingImages.length === 0) {
        const slash = parseSlash(input);
        if (slash) {
          if (!sock.sendSlash(slash.cmd, slash.args)) {
            dispatch({ type: 'SET_ERROR', error: 'socket is not connected' });
            return;
          }
          if (slash.cmd === 'plan') dispatch({ type: 'SET_PLAN', on: true });
          if (slash.cmd === 'default') dispatch({ type: 'SET_PLAN', on: false });
          return;
        }
      }

      const sent = sock.sendTurn({
        input,
        model,
        effort,
        permissions,
        images: pendingImages.map((a) => ({ mime: a.mime, data: a.base64 })),
      });
      if (!sent) {
        dispatch({ type: 'SET_ERROR', error: 'socket is not connected' });
        return;
      }
      dispatch({ type: 'CLEAR_IMAGES' });
      dispatch({ type: 'PENDING', pending: true });
    },
    [],
  );

  const interruptTurn = useCallback(() => {
    socketRef.current?.sendInterrupt();
  }, []);

  const resolveApproval = useCallback((decision) => {
    const pending = latestInputsRef.current.pendingApproval;
    if (!pending) return;
    if (socketRef.current?.sendApproval(pending.approvalId, decision)) {
      writePromptBackup(latestInputsRef.current.sessionId, { approval: null });
      dispatch({ type: 'APPROVAL_CLEAR' });
    }
  }, []);

  // answers: { <question_id>: [string, ...] }
  const resolveUserInput = useCallback((answers) => {
    const pending = latestInputsRef.current.pendingUserInput;
    if (!pending) return;
    if (socketRef.current?.sendUserInput(pending.callId, answers || {})) {
      writePromptBackup(latestInputsRef.current.sessionId, { userInput: null });
      dispatch({ type: 'USER_INPUT_CLEAR' });
    }
  }, []);
  const cancelUserInput = useCallback(() => {
    const pending = latestInputsRef.current.pendingUserInput;
    if (!pending) return;
    // Empty answers map → daemon returns { answers: {} } and codex
    // treats every question as "skipped".
    if (socketRef.current?.sendUserInput(pending.callId, {})) {
      writePromptBackup(latestInputsRef.current.sessionId, { userInput: null });
      dispatch({ type: 'USER_INPUT_CLEAR' });
    }
  }, []);

  const attachImage = useCallback(async (file) => {
    try {
      const base64 = await readAsBase64(file);
      const dataUrl = `data:${file.type};base64,${base64}`;
      dispatch({
        type: 'ATTACH_IMAGE',
        image: { dataUrl, mime: file.type, base64, label: file.name.slice(-32) },
      });
    } catch (t) {
      dispatch({ type: 'SET_ERROR', error: `image: ${t.message || 'read failed'}` });
    }
  }, []);

  const removeImage = useCallback((index) => {
    dispatch({ type: 'REMOVE_IMAGE', index });
  }, []);

  const attachExistingSession = useCallback((sid) => {
    if (!sid) return;
    if (reconnectRef.current) {
      clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
    }
    userClosedRef.current = false;
    reconnectAttemptRef.current = 0;
    if (socketRef.current) {
      socketRef.current.close();
      socketRef.current = null;
    }
    dispatch({ type: 'SESSION_RESET', status: STATUS.Opening });
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Session });
    dispatch({
      type: 'SESSION_ATTACHED',
      session: { sessionId: sid, hostId: null },
    });
    attachSocket(sid, { replayFromStart: true });
  }, [attachSocket]);

  // --- URL router ---
  //
  // Two-way sync: any in-app navigation (changing screen, switching host,
  // running a search, browsing a directory) reflects into window.location,
  // and any incoming URL (initial load, bookmark, browser back/forward)
  // replays through the navigation callbacks above.
  //
  // pushState is used only when the screen name changes (that's a real
  // navigation the user should be able to back-button out of). Intra-screen
  // changes — typing in the search box, tweaking mode, cd'ing the file
  // browser — use replaceState so they don't spam the back-button stack.
  const urlReadyRef = useRef(false);
  const lastScreenRef = useRef(null);
  const applyRouteRef = useRef(null);

  applyRouteRef.current = (route) => {
    if (route.screen === SCREENS.Hosts) {
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
      return;
    }
    if (route.screen === SCREENS.Search) {
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Search });
      if (route.mode) dispatch({ type: 'SEARCH_MODE', mode: route.mode });
      if (route.rerank) dispatch({ type: 'SEARCH_RERANK', rerank: route.rerank });
      if (route.hostId) dispatch({ type: 'SELECT_HOST', id: route.hostId });
      apiRef.current
        .searchConfig()
        .then((config) => dispatch({ type: 'SEARCH_CONFIG', config }))
        .catch(() => {});
      if (route.query) {
        dispatch({ type: 'SEARCH_QUERY', query: route.query });
        searchChats(route.query, { mode: route.mode || 'hybrid', rerank: route.rerank || 'auto' });
      }
      return;
    }
    if (route.screen === SCREENS.Threads && route.hostId) {
      dispatch({ type: 'SELECT_HOST', id: route.hostId });
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Threads });
      refreshThreads(route.hostId);
      return;
    }
    if (route.screen === SCREENS.Files && route.hostId) {
      dispatch({ type: 'SELECT_HOST', id: route.hostId });
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Files });
      browseDir(route.path || '/');
      return;
    }
    if (route.screen === SCREENS.Session && route.sessionId) {
      attachExistingSession(route.sessionId);
      return;
    }
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
  };

  useEffect(() => {
    const initial = parseUrl(window.location);
    lastScreenRef.current = initial.screen;
    applyRouteRef.current?.(initial);
    urlReadyRef.current = true;
    const onPop = () => applyRouteRef.current?.(parseUrl(window.location));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  useEffect(() => {
    if (!urlReadyRef.current) return;
    const next = buildUrl(state);
    const current = window.location.pathname + window.location.search;
    if (next === current) return;
    if (state.screen !== lastScreenRef.current) {
      window.history.pushState({ remotex: true }, '', next);
    } else {
      window.history.replaceState({ remotex: true }, '', next);
    }
    lastScreenRef.current = state.screen;
    // Surgical deps: re-fire only when a URL-bearing slice of state changes.
    // Listing the whole state would push/replace history on every keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    state.screen,
    state.selectedHostId,
    state.session?.sessionId,
    state.browsePath,
    state.searchQuery,
    state.searchMode,
    state.searchRerank,
  ]);

  // --- public surface ---

  return useMemo(
    () => ({
      state,
      setToken: (t) => dispatch({ type: 'SET_TOKEN', token: t }),
      setSearchQuery: (query) => dispatch({ type: 'SEARCH_QUERY', query }),
      setSearchMode: (mode) => dispatch({ type: 'SEARCH_MODE', mode }),
      setSearchRerank: (rerank) => dispatch({ type: 'SEARCH_RERANK', rerank }),
      setModel: (m) => dispatch({ type: 'SET_MODEL', model: m }),
      setEffort: (e) => dispatch({ type: 'SET_EFFORT', effort: e }),
      setPermissions: (p) => dispatch({ type: 'SET_PERMS', permissions: p }),
      clearFeedback: () => dispatch({ type: 'CLEAR_FEEDBACK' }),
      clearError: () => dispatch({ type: 'SET_ERROR', error: null }),
      goToHosts,
      goToThreads,
      goToSearch,
      goToSession,
      goToFiles,
      refreshHosts,
      openHost,
      refreshThreads,
      searchChats,
      openSearchResult,
      browseDir,
      browseUp,
      createFolder,
      listDirectory,
      workspaceListDirectory,
      workspaceReadFile,
      workspaceDeleteFile,
      workspaceRenameFile,
      workspaceUploadFile,
      openSession,
      openOrchestratorSession,
      startSessionInCurrentPath: () =>
        openSession({ cwd: latestInputsRef.current.browsePath || null }),
      closeSession,
      sendTurn,
      sendSlash,
      interruptTurn,
      resolveApproval,
      resolveUserInput,
      cancelUserInput,
      attachImage,
      removeImage,
      setPreferredKind: (k) => dispatch({ type: 'SET_PREFERRED_KIND', kind: k }),
      // Internal escape hatch: WorkspaceFilesDrawer needs apiRef directly
      // so a single component can call read/rename/delete/upload without
      // dragging four wrappers through props.
      apiRef,
    }),
    [
      state,
      goToHosts,
      // goToDashboard is stable (useCallback with no deps) so it never
      // changes; including it in this list does nothing useful.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      goToThreads,
      goToSearch,
      goToSession,
      goToFiles,
      refreshHosts,
      openHost,
      refreshThreads,
      searchChats,
      openSearchResult,
      browseDir,
      browseUp,
      createFolder,
      listDirectory,
      workspaceListDirectory,
      workspaceReadFile,
      workspaceDeleteFile,
      workspaceRenameFile,
      workspaceUploadFile,
      openSession,
      openOrchestratorSession,
      closeSession,
      sendTurn,
      sendSlash,
      interruptTurn,
      resolveApproval,
      resolveUserInput,
      cancelUserInput,
      attachImage,
      removeImage,
    ],
  );
}

// --- helpers ---

function buildItemEvent(data) {
  const id = data.item_id;
  const replayed = Boolean(data.replayed);
  switch (data.item_type) {
    case 'agent_reasoning':
      return { id, role: 'reasoning', text: data.text || '', completed: replayed, replayed };
    case 'agent_message':
      return { id, role: 'agent', text: data.text || '', completed: replayed };
    case 'tool_call':
      return {
        id,
        role: 'tool',
        tool: data.tool || 'tool',
        command: data.args?.command || '',
        output: data.output || '',
        completed: replayed,
      };
    case 'mcp_tool_call':
      return {
        id,
        role: 'tool',
        tool: formatMcpTool(data),
        command: formatJsonPreview(data.arguments),
        output: formatMcpOutput(data),
        completed: replayed || data.status === 'completed' || data.status === 'failed',
        toolKind: 'mcp',
        status: data.status || '',
        durationMs: data.duration_ms,
        error: data.error || '',
        rawArguments: data.arguments,
        rawResult: data.result,
      };
    case 'dynamic_tool_call':
      return {
        id,
        role: 'tool',
        tool: formatDynamicTool(data),
        command: formatJsonPreview(data.arguments),
        output: formatDynamicOutput(data),
        completed: replayed || data.status === 'completed' || data.status === 'failed',
        toolKind: 'dynamic',
        status: data.status || '',
        durationMs: data.duration_ms,
        error: data.error || '',
        rawArguments: data.arguments,
        rawResult: data.content_items,
      };
    case 'collab_agent_tool_call':
      return {
        id,
        role: 'tool',
        tool: formatCollabTool(data.tool),
        command: data.prompt || '',
        output: formatCollabStatus(data),
        completed: replayed || data.status === 'completed' || data.status === 'failed',
      };
    case 'user_message':
      return { id, role: 'user', text: data.text || '', imageCount: data.image_count || 0 };
    default:
      return { id, role: 'system', label: data.item_type || 'item', detail: '' };
  }
}

function formatCollabTool(tool) {
  switch (tool) {
    case 'spawnAgent':
      return 'spawn agent';
    case 'sendInput':
      return 'send input';
    case 'resumeAgent':
      return 'resume agent';
    case 'wait':
      return 'wait agents';
    case 'closeAgent':
      return 'close agent';
    default:
      return tool || 'subagent';
  }
}

function formatCollabStatus(data) {
  const status = data.status || 'inProgress';
  const receivers = Array.isArray(data.receiver_thread_ids) ? data.receiver_thread_ids.length : 0;
  const model = data.model ? ` · ${data.model}` : '';
  const receiverText = receivers ? ` · ${receivers} thread${receivers === 1 ? '' : 's'}` : '';
  return `${status}${model}${receiverText}`;
}

function formatMcpTool(data = {}) {
  const name = [data.server, data.tool].filter(Boolean).join('.');
  return `MCP · ${name || 'tool'}`;
}

function formatDynamicTool(data = {}) {
  const name = [data.namespace, data.tool].filter(Boolean).join('.');
  return `TOOL · ${name || data.tool || 'dynamic'}`;
}

function formatMcpOutput(data = {}) {
  const parts = [data.status || 'inProgress'];
  if (Number.isFinite(data.duration_ms)) parts.push(`${data.duration_ms}ms`);
  if (data.error) parts.push(`error: ${data.error}`);
  const text = extractMcpResultText(data.result);
  if (text) parts.push(text);
  return parts.join('\n');
}

function formatDynamicOutput(data = {}) {
  const parts = [data.status || 'inProgress'];
  if (Number.isFinite(data.duration_ms)) parts.push(`${data.duration_ms}ms`);
  if (typeof data.success === 'boolean') parts.push(data.success ? 'success' : 'failed');
  if (Array.isArray(data.content_items) && data.content_items.length) {
    parts.push(formatJsonPreview(data.content_items));
  }
  return parts.filter(Boolean).join('\n');
}

function extractMcpResultText(result) {
  if (!result || typeof result !== 'object') return '';
  const content = Array.isArray(result.content) ? result.content : [];
  const texts = content
    .map((item) => {
      if (typeof item === 'string') return item;
      if (item && typeof item === 'object' && typeof item.text === 'string') return item.text;
      return '';
    })
    .filter(Boolean);
  if (texts.length) return texts.join('\n');
  if ('structuredContent' in result) return formatJsonPreview(result.structuredContent);
  return '';
}

function formatJsonPreview(value) {
  if (value === undefined || value === null || value === '') return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function normalizeSubagentEvent(data = {}) {
  return {
    id: data.item_id || '',
    agent_id: data.agent_id || firstString(data.receiver_thread_ids) || data.sender_thread_id || '',
    kind: data.kind || '',
    tool: data.tool || '',
    label: data.label || formatCollabTool(data.tool),
    status: subagentEventStatus(data),
    prompt: data.prompt || '',
    model: data.model || '',
    reasoning_effort: data.reasoning_effort || '',
    sender_thread_id: data.sender_thread_id || '',
    receiver_thread_ids: Array.isArray(data.receiver_thread_ids)
      ? data.receiver_thread_ids.filter(Boolean)
      : [],
    agents_states: normalizeAgentStates(data.agents_states),
  };
}

function firstString(value) {
  return Array.isArray(value) ? value.find((v) => typeof v === 'string' && v) || '' : '';
}

function subagentEventStatus(data = {}) {
  if (data.status) return data.status;
  if (data.kind === 'item-completed') return 'completed';
  if (data.kind === 'item-started') return 'inProgress';
  return 'inProgress';
}

function normalizeAgentStates(raw) {
  if (!raw) return [];
  const entries = Array.isArray(raw)
    ? raw.map((value, idx) => [value?.thread_id || value?.threadId || value?.id || String(idx), value])
    : Object.entries(raw);
  return entries
    .filter(([threadId, value]) => threadId && value && typeof value === 'object')
    .map(([threadId, value]) => ({
      thread_id: threadId,
      status: value.status || value.state || 'running',
      message: value.message || value.summary || value.error || '',
    }));
}

function upsertSubagentEvent(list = [], event = {}) {
  const prev = Array.isArray(list) ? list : [];
  const id = event.id || `${event.kind || 'event'}:${event.tool || 'subagent'}:${prev.length}`;
  const nextEvent = { ...event, id };
  const idx = prev.findIndex((item) => item.id === id);
  const next = idx >= 0
    ? prev.map((item, i) => (i === idx ? mergeSubagentEvent(item, nextEvent) : item))
    : [...prev, nextEvent];
  return assignSubagentDepths(next.slice(-SUBAGENT_ACTIVITY_MAX));
}

function mergeSubagentEvent(prev, incoming) {
  return {
    ...prev,
    ...incoming,
    label: incoming.label || prev.label,
    status: incoming.status || prev.status,
    prompt: incoming.prompt || prev.prompt,
    model: incoming.model || prev.model,
    reasoning_effort: incoming.reasoning_effort || prev.reasoning_effort,
    sender_thread_id: incoming.sender_thread_id || prev.sender_thread_id,
    receiver_thread_ids: incoming.receiver_thread_ids?.length
      ? incoming.receiver_thread_ids
      : prev.receiver_thread_ids || [],
    agents_states: incoming.agents_states?.length
      ? incoming.agents_states
      : prev.agents_states || [],
  };
}

function assignSubagentDepths(events) {
  const receiverDepth = new Map();
  return events.map((event) => {
    let depth = 1;
    if (event.sender_thread_id && receiverDepth.has(event.sender_thread_id)) {
      depth = receiverDepth.get(event.sender_thread_id) + 1;
    }
    const boundedDepth = Math.min(Math.max(depth, 1), 4);
    for (const receiverId of event.receiver_thread_ids || []) {
      receiverDepth.set(receiverId, boundedDepth);
    }
    const agentsStates = (event.agents_states || []).map((agent) => ({
      ...agent,
      depth: agent.thread_id && receiverDepth.has(agent.thread_id)
        ? receiverDepth.get(agent.thread_id)
        : boundedDepth,
    }));
    return { ...event, depth: boundedDepth, agents_states: agentsStates };
  });
}

function upsertAgentTranscript(agents = {}, payload = {}) {
  const agentId = payload.agent_id;
  if (!agentId) return agents || {};
  const prev = agents?.[agentId] || {
    id: agentId,
    label: payload.label || agentId,
    parent_agent_id: payload.parent_agent_id || null,
    step_id: payload.step_id || null,
    thread_id: payload.thread_id || null,
    depth: payload.depth ?? 0,
    status: 'running',
    events: [],
  };
  const next = {
    ...prev,
    label: payload.label || prev.label,
    parent_agent_id: payload.parent_agent_id ?? prev.parent_agent_id,
    step_id: payload.step_id ?? prev.step_id,
    thread_id: payload.thread_id ?? prev.thread_id,
    depth: payload.depth ?? prev.depth,
    status: agentStatusFromPayload(payload, prev.status),
    events: applyTranscriptEvent(prev.events, payload),
  };
  return { ...(agents || {}), [agentId]: next };
}

function agentStatusFromPayload(payload, prevStatus) {
  if (payload.kind === 'turn-completed') {
    return payload.error ? 'failed' : 'completed';
  }
  if (payload.kind === 'turn-started' || payload.kind === 'item-started' || payload.kind === 'item-delta') {
    return 'running';
  }
  return prevStatus || 'running';
}

function applyTranscriptEvent(events = [], payload = {}) {
  const kind = payload.kind;
  if (kind === 'item-started') {
    const event = buildItemEvent({ ...payload, replayed: false });
    return event ? upsertTranscriptItem(events, event) : events;
  }
  if (kind === 'item-delta') {
    return appendTranscriptDelta(events, payload);
  }
  if (kind === 'item-completed') {
    const event = buildItemEvent({ ...payload, replayed: false });
    const patch = completedPatchForItem(payload);
    return upsertTranscriptItem(events, { ...event, ...patch, completed: true });
  }
  if (kind === 'approval-request') {
    return upsertTranscriptItem(events, {
      id: payload.approval_id || `approval-${events.length}`,
      role: 'system',
      label: 'approval',
      detail: payload.reason || payload.command || 'approval requested',
    });
  }
  if (kind === 'user-input-request') {
    return upsertTranscriptItem(events, {
      id: payload.call_id || `input-${events.length}`,
      role: 'system',
      label: 'prompt',
      detail: 'agent requested input',
    });
  }
  return events;
}

function completedPatchForItem(data = {}) {
  if (data.item_type === 'agent_message' || data.item_type === 'agent_reasoning') {
    return data.text ? { text: data.text } : {};
  }
  if (data.item_type === 'tool_call') {
    return data.output ? { output: data.output } : {};
  }
  if (data.item_type === 'mcp_tool_call') {
    return { output: formatMcpOutput(data) };
  }
  if (data.item_type === 'dynamic_tool_call') {
    return { output: formatDynamicOutput(data) };
  }
  if (data.item_type === 'collab_agent_tool_call') {
    return { output: formatCollabStatus(data) };
  }
  return {};
}

function upsertTranscriptItem(events = [], event) {
  if (!event?.id) return events;
  const idx = events.findIndex((item) => item.id === event.id);
  if (idx >= 0) {
    return events.map((item, i) => (i === idx ? { ...item, ...event } : item));
  }
  return [...events, event];
}

function appendTranscriptDelta(events = [], payload = {}) {
  const id = payload.item_id;
  if (!id) return events;
  const idx = events.findIndex((item) => item.id === id);
  if (idx < 0) {
    const event = buildItemEvent({
      ...payload,
      item_id: id,
      output: payload.item_type === 'mcp_tool_call' ? payload.delta || '' : '',
      text: payload.item_type !== 'mcp_tool_call' ? payload.delta || '' : '',
    });
    return event ? [...events, event] : events;
  }
  return events.map((event, i) => {
    if (i !== idx) return event;
    if (event.role === 'tool') {
      return { ...event, output: (event.output || '') + (payload.delta || '') };
    }
    return { ...event, text: (event.text || '') + (payload.delta || '') };
  });
}

async function readAsBase64(file) {
  const buf = await file.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}
