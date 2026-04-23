// Top-level hook that owns all web-client state. Equivalent to
// Android's RemotexViewModel — host list, thread list, file browser,
// and the session machine all live here, plus navigation. Kept as one
// reducer so transitions are easy to reason about, mirroring the
// Kotlin ViewModel one-state-object shape.

import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';
import { RelayClient } from '../api/relayClient';
import { SessionSocket } from '../api/sessionSocket';
import { SCREENS, STATUS, effortsFor } from '../config';
import { parseSlash } from '../util/slash';
import { parentPath } from '../util/path';
import { buildUrl, parseUrl } from '../util/url';

const TOKEN_KEY = 'remotex.userToken';
const loadToken = () => {
  try {
    return localStorage.getItem(TOKEN_KEY) || 'demo-user-token';
  } catch {
    return 'demo-user-token';
  }
};

const initialState = {
  screen: SCREENS.Hosts,
  userToken: loadToken(),
  hosts: [],
  hostsLoading: false,
  selectedHostId: null,
  threads: [],
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
  error: null,
  // hostTelemetry[hostId] = {
  //   current: { cpu:{percent,cores,temp_c}, memory:{used_bytes,total_bytes,percent},
  //              gpu:{name,percent,mem_used_mb,mem_total_mb,temp_c}|null,
  //              network:{up_bps,down_bps}, uptime_s, load_avg:[1m,5m,15m], ts },
  //   history: { cpu:[], mem:[], gpu:[], up:[], down:[] },  // rolling, newest last
  //   lastUpdate: epoch_ms
  // }
  hostTelemetry: {},
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

    case 'HOSTS':
      return { ...state, hosts: action.hosts, hostsLoading: false };
    case 'HOSTS_LOADING':
      return { ...state, hostsLoading: action.loading };

    case 'SELECT_HOST':
      return { ...state, selectedHostId: action.id };
    case 'THREADS':
      return { ...state, threads: action.threads, threadsLoading: false };
    case 'THREADS_LOADING':
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
        slashFeedback: null,
        pendingImages: [],
        pending: false,
        planMode: false,
        status: action.status ?? STATUS.Idle,
      };
    case 'SESSION_ATTACHED':
      return { ...state, session: action.session, status: STATUS.Connecting };
    case 'SESSION_INFO':
      return { ...state, session: { ...(state.session || {}), ...action.info } };
    case 'SESSION_STATUS':
      return { ...state, status: action.status };

    case 'APPEND_EVENT':
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
        effort: effortsFor(action.model).includes(state.effort) ? state.effort : '',
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

    case 'SLASH_FEEDBACK':
      return { ...state, slashFeedback: action.text };
    case 'SET_PLAN':
      return { ...state, planMode: action.on };

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

  const handleFrame = useCallback((frame) => {
    if (frame.type === 'attached') {
      reconnectAttemptRef.current = 0;
      dispatch({ type: 'SESSION_STATUS', status: STATUS.Connecting });
      dispatch({ type: 'SET_ERROR', error: null });
      dispatch({
        type: 'SESSION_INFO',
        info: { sessionId: frame.session_id, hostId: frame.host_id },
      });
      return;
    }
    if (frame.type === 'session-closed') {
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
          info: { model: data.model, cwd: data.cwd },
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
        }
        dispatch({ type: 'COMPLETE_EVENT', id: data.item_id, patch });
        return;
      }
      case 'turn-completed':
        dispatch({ type: 'PENDING', pending: false });
        if (data.error) dispatch({ type: 'SET_ERROR', error: data.error });
        return;
      case 'approval-request':
        dispatch({
          type: 'APPROVAL_REQUEST',
          prompt: {
            approvalId: data.approval_id,
            kind: data.kind,
            reason: data.reason,
            command: data.command,
            cwd: data.cwd,
            decisions: data.decisions || ['accept', 'decline'],
          },
        });
        return;
      case 'slash-ack': {
        const cmd = data.command || '?';
        const ok = data.ok === true;
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
        if (data.status === 'resumed') {
          dispatch({ type: 'SESSION_STATUS', status: STATUS.Connected });
          dispatch({
            type: 'SESSION_INFO',
            info: { model: data.model, cwd: data.cwd },
          });
          dispatch({ type: 'SET_ERROR', error: null });
        } else if (data.status === 'resume-failed') {
          dispatch({ type: 'SESSION_STATUS', status: STATUS.Error });
          dispatch({
            type: 'SET_ERROR',
            error: data.error || 'Saved chat could not be resumed.',
          });
          dispatch({ type: 'PENDING', pending: false });
        }
        return;
      case 'turn-started':
      case 'history-begin':
      case 'history-end':
        return;
      default:
        return;
    }
  }, []);

  // attachSocket + scheduleReconnect form a cycle (attach emits
  // disconnect → schedule → reattach). Use refs to break the cycle so
  // each callback is stable and neither needs the other in its deps.
  const attachSocketRef = useRef(null);
  const scheduleReconnectRef = useRef(null);

  const attachSocket = useCallback(
    (sid) => {
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
    closeSession();
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
  }, [closeSession]);

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
      dispatch({ type: 'THREADS_LOADING', loading: true });
      try {
        const threads = await apiRef.current.listThreads(target, 25);
        dispatch({ type: 'THREADS', threads });
      } catch (t) {
        dispatch({ type: 'THREADS_LOADING', loading: false });
        dispatch({ type: 'SET_ERROR', error: t.message });
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

  // --- session ---

  const openSession = useCallback(
    async ({ threadId = null, cwd = null, hostId: hostOverride = null } = {}) => {
      const hostId = hostOverride || latestInputsRef.current.selectedHostId;
      if (!hostId) return;
      closeSession();
      userClosedRef.current = false;
      dispatch({ type: 'SESSION_RESET', status: STATUS.Opening });
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
    [attachSocket, closeSession],
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
          sock.sendSlash(slash.cmd, slash.args);
          if (slash.cmd === 'plan') dispatch({ type: 'SET_PLAN', on: true });
          if (slash.cmd === 'default') dispatch({ type: 'SET_PLAN', on: false });
          return;
        }
      }

      const userId = `u-${Math.random().toString(36).slice(2, 10)}`;
      dispatch({
        type: 'APPEND_EVENT',
        event: {
          id: userId,
          role: 'user',
          text: input,
          imageUrls: pendingImages.map((a) => a.dataUrl),
        },
      });
      dispatch({ type: 'CLEAR_IMAGES' });
      dispatch({ type: 'PENDING', pending: true });
      sock.sendTurn({
        input,
        model,
        effort,
        permissions,
        images: pendingImages.map((a) => ({ mime: a.mime, data: a.base64 })),
      });
    },
    [],
  );

  const interruptTurn = useCallback(() => {
    socketRef.current?.sendInterrupt();
  }, []);

  const resolveApproval = useCallback((decision) => {
    const pending = latestInputsRef.current.pendingApproval;
    if (!pending) return;
    socketRef.current?.sendApproval(pending.approvalId, decision);
    dispatch({ type: 'APPROVAL_CLEAR' });
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
    // Session URLs aren't really resumable — the session id is an ephemeral
    // relay handle. Fall back to hosts rather than a blank screen.
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      openSession,
      startSessionInCurrentPath: () =>
        openSession({ cwd: latestInputsRef.current.browsePath || null }),
      closeSession,
      sendTurn,
      interruptTurn,
      resolveApproval,
      attachImage,
      removeImage,
    }),
    [
      state,
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
      openSession,
      closeSession,
      sendTurn,
      interruptTurn,
      resolveApproval,
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
    case 'user_message':
      return { id, role: 'user', text: data.text || '' };
    default:
      return { id, role: 'system', label: data.item_type || 'item', detail: '' };
  }
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
