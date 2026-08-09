// Top-level hook that owns all web-client state. Equivalent to
// Android's RemotexViewModel — host list, thread list, file browser,
// and the session machine all live here, plus navigation. Kept as one
// reducer so transitions are easy to reason about, mirroring the
// Kotlin ViewModel one-state-object shape.

import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';
import { RelayClient } from '../api/relayClient';
import { SessionSocket } from '../api/sessionSocket';
import {
  FALLBACK_MODEL_OPTIONS,
  MAX_FILE_BYTES,
  SCREENS,
  STATUS,
  effortsFor,
  formatBytes,
} from '../config';
import { parseSlash } from '../util/slash';
import { parentPath } from '../util/path';
import { hostHomePath } from '../util/host';
import { buildUrl, parseUrl } from '../util/url';
import { getCachedPreview, prefetchPreview } from '../util/threadPreview';

const PROMPT_BACKUP_PREFIX = 'remotex.pendingPrompts.';

function promptBackupKey(sessionId) {
  return `${PROMPT_BACKUP_PREFIX}${sessionId}`;
}

// Backups are queue-shaped: {approvals: [...], userInputs: [...]}. The
// pre-queue shape ({approval, userInput}) is still read so a tab that was
// open across a deploy doesn't lose its unanswered prompt.
function readPromptBackup(sessionId) {
  if (!sessionId) return null;
  try {
    const raw = sessionStorage.getItem(promptBackupKey(sessionId));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    return {
      approvals: Array.isArray(parsed.approvals)
        ? parsed.approvals
        : [parsed.approval].filter(Boolean),
      userInputs: Array.isArray(parsed.userInputs)
        ? parsed.userInputs
        : [parsed.userInput].filter(Boolean),
    };
  } catch {
    return null;
  }
}

function writePromptBackup(sessionId, { approvals = [], userInputs = [] }) {
  if (!sessionId) return;
  try {
    if (approvals.length === 0 && userInputs.length === 0) {
      sessionStorage.removeItem(promptBackupKey(sessionId));
      return;
    }
    sessionStorage.setItem(
      promptBackupKey(sessionId),
      JSON.stringify({ approvals, userInputs }),
    );
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

// Every decision codex accepts, for a prompt that didn't name its own.
// Kept identical in RemotexViewModel.kt and RemotexViewModel.swift.
export const DEFAULT_APPROVAL_DECISIONS = ['accept', 'acceptForSession', 'decline', 'cancel'];

export function attachedTurnInFlight(frame) {
  return typeof frame?.turn_in_flight === 'boolean' ? frame.turn_in_flight : null;
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
    decisions: data.decisions || DEFAULT_APPROVAL_DECISIONS,
    // Relay-assigned queue position; absent on live request frames.
    order: Number.isFinite(data.order) ? data.order : undefined,
  };
}

function normalizeUserInputPrompt(data = {}) {
  if (!data.call_id) return null;
  return {
    callId: data.call_id,
    turnId: data.turn_id,
    questions: Array.isArray(data.questions) ? data.questions : [],
    order: Number.isFinite(data.order) ? data.order : undefined,
  };
}

// The relay's pending-prompts frame carries EVERY unanswered prompt, in
// arrival order. Both lists stay lists — a second concurrent prompt must
// never displace the first (contract F).
function normalizePromptSnapshot(frame = {}) {
  const approvals = Array.isArray(frame.approvals) ? frame.approvals : [];
  const userInputs = Array.isArray(frame.user_inputs) ? frame.user_inputs : [];
  return {
    approvals: approvals.map((d) => normalizeApprovalPrompt(d || {})).filter(Boolean),
    userInputs: userInputs.map((d) => normalizeUserInputPrompt(d || {})).filter(Boolean),
  };
}

// --- pending-prompt queues (contract F) ---
//
// Prompts are keyed by approval_id / call_id so a replayed or duplicated
// frame updates in place instead of double-inserting. The head is what
// the UI renders; answering it pops the head and reveals the next.

export function enqueuePrompt(queue, prompt, keyOf) {
  if (!prompt) return queue;
  const idx = queue.findIndex((p) => keyOf(p) === keyOf(prompt));
  if (idx === -1) return [...queue, prompt];
  // Same prompt again (reconnect replay): refresh the payload, keep the slot.
  const next = queue.slice();
  next[idx] = prompt;
  return next;
}

export function dequeuePrompt(queue, key, keyOf) {
  // A resolution frame without an id can only mean the head — that is the
  // one every client is rendering.
  if (!key) return queue.slice(1);
  return queue.filter((p) => keyOf(p) !== key);
}

// Merge a relay snapshot into the local queue. The snapshot decides
// MEMBERSHIP (a prompt another client answered is gone from it) and, when
// it carries the relay's `order`, ORDER too — that is what puts a claim
// the relay handed back after a failed forward into its original slot
// rather than behind a prompt that arrived later (contract F). Without
// `order` (the tab's own sessionStorage backup) the local order stands.
export function reconcileQueue(queue, incoming, keyOf) {
  const byKey = new Map(incoming.map((p) => [keyOf(p), p]));
  const kept = [];
  for (const existing of queue) {
    const key = keyOf(existing);
    if (!byKey.has(key)) continue;
    kept.push(byKey.get(key));
    byKey.delete(key);
  }
  const merged = [...kept, ...byKey.values()];
  if (merged.every((p) => Number.isFinite(p?.order))) {
    merged.sort((a, b) => a.order - b.order);
  }
  return merged;
}

const approvalKey = (p) => p?.approvalId;
const userInputKey = (p) => p?.callId;

// Heads are derived, never stored twice: every queue write goes through
// here so `pendingApproval` / `pendingUserInput` can't drift from the
// queue they front.
function withPromptQueues(state, approvals, userInputs) {
  return {
    ...state,
    pendingApprovals: approvals,
    pendingUserInputs: userInputs,
    pendingApproval: approvals[0] || null,
    pendingUserInput: userInputs[0] || null,
  };
}

function normalizeGoal(goal) {
  if (!goal || typeof goal !== 'object') return null;
  const statusText = String(goal.status || '').replace(/[-_]/g, '').toLowerCase();
  const status = statusText === 'budgetlimited'
    ? 'budgetLimited'
    : statusText === 'active'
      ? 'active'
      : statusText === 'paused'
        ? 'paused'
        : statusText === 'complete'
          ? 'complete'
          : (goal.status || '');
  const number = (value, fallback = 0) => {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string' && /^-?\d+$/.test(value)) return parseInt(value, 10);
    return fallback;
  };
  const budget = goal.token_budget ?? goal.tokenBudget ?? null;
  return {
    thread_id: goal.thread_id || goal.threadId || '',
    objective: goal.objective || '',
    status,
    token_budget: budget === null || budget === undefined ? null : number(budget, null),
    tokens_used: number(goal.tokens_used ?? goal.tokensUsed),
    time_used_seconds: number(goal.time_used_seconds ?? goal.timeUsedSeconds),
    created_at: goal.created_at ?? goal.createdAt ?? null,
    updated_at: goal.updated_at ?? goal.updatedAt ?? null,
  };
}

function slashCommandText(cmd, args = '') {
  const name = String(cmd || '').replace(/^\/+/, '').trim();
  const rest = String(args || '').trim();
  return `/${name}${rest ? ` ${rest}` : ''}`;
}

function slashEventId(prefix, cmd) {
  return `${prefix}-${String(cmd || 'slash')}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function shouldEchoSlashCommand(cmd) {
  return cmd !== 'plan' && cmd !== 'default';
}

function appendSlashUserEvent(dispatch, cmd, args = '') {
  if (!shouldEchoSlashCommand(cmd)) return;
  dispatch({
    type: 'APPEND_EVENT',
    event: {
      id: slashEventId('slash-user', cmd),
      role: 'user',
      text: slashCommandText(cmd, args),
      completed: true,
      slash: true,
    },
  });
}

function appendSlashAckEvent(dispatch, cmd, ok, data = {}) {
  if (!shouldEchoSlashCommand(cmd)) return;
  dispatch({
    type: 'APPEND_EVENT',
    event: {
      id: slashEventId('slash-ack', cmd),
      role: 'system',
      label: ok ? `/${cmd}` : `/${cmd} failed`,
      detail: data.message || data.error || (ok ? 'ok' : 'unknown error'),
      completed: true,
      slash: true,
    },
  });
}

export const initialState = {
  screen: SCREENS.Hosts,
  userToken: '',
  // "remember on this device": ON keeps the token in localStorage, OFF
  // parks it in sessionStorage so it dies with the tab.
  rememberToken: true,
  hosts: [],
  hostsLoading: false,
  selectedHostId: null,
  threads: [],
  threadsHostId: null,
  threadsLoading: false,
  browsePath: '',
  browseEntries: [],
  browseLoading: false,
  status: STATUS.Idle,
  session: null,
  events: [],
  // Tail-first history: the daemon ships only the last couple of turns on
  // open; scrolling up pages older ones in via `history-more`. `historyTick`
  // bumps on every committed batch so EventStream can anchor scroll.
  historyHasMore: false,
  historyOldest: 0,
  historyLoading: false,
  historyTick: 0,
  historyPrepend: false,
  pending: false,
  model: '',
  effort: 'medium',
  permissions: 'default',
  pendingImages: [],
  // Ordered queues of unanswered codex prompts (contract F). pendingApproval
  // / pendingUserInput are the rendered heads, derived from these.
  pendingApprovals: [],
  pendingUserInputs: [],
  pendingApproval: null,
  pendingUserInput: null,
  slashFeedback: null,
  planMode: false,
  goal: null,
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
  // Model picker list: asked of the selected host first, then the relay's
  // hostless /api/models response, then this "let codex decide" fallback.
  modelOptions: FALLBACK_MODEL_OPTIONS,
};

const TELEMETRY_WINDOW_MS = 30000; // real 30-second sliding window

// --- reducer ---

export function reducer(state, action) {
  switch (action.type) {
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
      return {
        ...state,
        selectedHostId: action.id,
        threads: action.id === state.threadsHostId ? state.threads : [],
        threadsHostId: action.id === state.threadsHostId ? state.threadsHostId : null,
        threadsLoading: false,
        browsePath: action.browsePath || state.browsePath,
        browseEntries: action.browsePath && action.browsePath !== state.browsePath
          ? []
          : state.browseEntries,
        browseLoading: false,
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
      return withPromptQueues({
        ...state,
        events: [],
        historyHasMore: false,
        historyOldest: 0,
        historyLoading: false,
        historyPrepend: false,
        session: null,
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
        goal: null,
      }, [], []);
    case 'GOAL_SET':
      return { ...state, goal: action.goal || null };
    case 'GOAL_CLEAR':
      return { ...state, goal: null };
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

    case 'HISTORY_LOADING':
      return { ...state, historyLoading: true };
    case 'HISTORY_COMMIT': {
      // One commit per replay batch: the buffered tail (or an older chunk)
      // lands in a single render instead of streaming past the user.
      // Prepending before existing events is correct for both cases — on
      // the initial tail, `events` holds at most live frames that raced in.
      // The authoritative tail replaces any instant-paint preview rows.
      const kept = state.events.filter((e) => !String(e.id).startsWith('preview_'));
      const seen = new Set(kept.map((e) => e.id));
      const incoming = action.events.filter((e) => e && !seen.has(e.id));
      return {
        ...state,
        events: [...incoming, ...kept],
        historyHasMore: !!action.hasMore,
        historyOldest: action.oldest,
        historyLoading: false,
        historyTick: state.historyTick + 1,
        historyPrepend: !!action.prepend,
      };
    }
    case 'APPEND_EVENT': {
      const duplicate = action.event?.id && state.events.some((e) => e.id === action.event.id);
      if (!duplicate) return { ...state, events: [...state.events, action.event] };
      if (!action.authoritative) return state;
      return {
        ...state,
        events: state.events.map((e) => {
          if (e.id !== action.event.id) return e;
          const repaired = { ...e, ...action.event };
          if ('completed' in e || 'completed' in action.event) {
            repaired.completed = Boolean(e.completed || action.event.completed);
          }
          return repaired;
        }),
      };
    }
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
      // keepPending: apply the patch but leave the item streaming — used by
      // item-patch, where codex resends a growing diff mid-edit.
      return {
        ...state,
        events: state.events.map((e) =>
          e.id === action.id
            ? { ...e, ...action.patch, completed: action.keepPending ? e.completed : true }
            : e,
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
      return withPromptQueues(
        state,
        enqueuePrompt(state.pendingApprovals, action.prompt, approvalKey),
        state.pendingUserInputs,
      );
    case 'APPROVAL_CLEAR':
      return withPromptQueues(
        state,
        dequeuePrompt(state.pendingApprovals, action.approvalId, approvalKey),
        state.pendingUserInputs,
      );

    case 'USER_INPUT_REQUEST':
      return withPromptQueues(
        state,
        state.pendingApprovals,
        enqueuePrompt(state.pendingUserInputs, action.prompt, userInputKey),
      );
    case 'USER_INPUT_CLEAR':
      return withPromptQueues(
        state,
        state.pendingApprovals,
        dequeuePrompt(state.pendingUserInputs, action.callId, userInputKey),
      );
    // Relay snapshot: authoritative about WHICH prompts are still open,
    // so it prunes prompts a peer client answered without discarding the
    // local ordering of the ones that remain.
    case 'PENDING_PROMPTS':
      return withPromptQueues(
        state,
        reconcileQueue(state.pendingApprovals, action.approvals || [], approvalKey),
        reconcileQueue(state.pendingUserInputs, action.userInputs || [], userInputKey),
      );

    case 'SLASH_FEEDBACK':
      return { ...state, slashFeedback: action.text };
    case 'SET_PLAN':
      return { ...state, planMode: action.on };

    case 'MODEL_OPTIONS': {
      const options = action.options;
      // A host that doesn't offer the currently-picked model (or effort)
      // would reject the turn, so fall back to "codex picks" rather than
      // sending something we know is invalid.
      const model = options.some((m) => m.id === state.model) ? state.model : '';
      const effort = effortsFor(model, options).includes(state.effort) ? state.effort : '';
      return { ...state, modelOptions: options, model, effort };
    }

    case 'TELEMETRY': {
      const hostId = action.hostId;
      if (!hostId || !action.data) return state;
      const prev = state.hostTelemetry[hostId];
      const prevHistory = prev?.history || { cpu: [], mem: [], gpu: [], up: [], down: [] };
      const d = action.data;
      const now = Date.now();
      const cutoff = now - TELEMETRY_WINDOW_MS;
      const num = (v) => (Number.isFinite(v) ? v : 0);
      // Real 30s sliding window. The poll carries the relay's ~30s ring, so we
      // rebuild the whole window from it — graphs open full instead of drawing
      // in from the right. age_ms keeps it clock-skew-proof. WS pushes (no
      // ring) just append; an empty ring also falls through so it can't wipe
      // accumulated history.
      let history;
      if (Array.isArray(action.history) && action.history.length) {
        const series = (pick) =>
          action.history
            .map((s) => ({ t: now - (s.age_ms || 0), v: num(pick(s.data || {})) }))
            .filter((p) => p.t >= cutoff);
        history = {
          cpu: series((x) => x.cpu?.percent),
          mem: series((x) => x.memory?.percent),
          gpu: series((x) => x.gpu?.percent),
          up: series((x) => x.network?.up_bps),
          down: series((x) => x.network?.down_bps),
        };
      } else {
        const push = (arr, v) => {
          const next = arr.filter((p) => p.t >= cutoff);
          next.push({ t: now, v: num(v) });
          return next;
        };
        history = {
          cpu: push(prevHistory.cpu, d.cpu?.percent ?? 0),
          mem: push(prevHistory.mem, d.memory?.percent ?? 0),
          gpu: push(prevHistory.gpu, d.gpu?.percent ?? 0),
          up: push(prevHistory.up, d.network?.up_bps ?? 0),
          down: push(prevHistory.down, d.network?.down_bps ?? 0),
        };
      }
      return {
        ...state,
        hostTelemetry: {
          ...state.hostTelemetry,
          [hostId]: { current: d, history, lastUpdate: now },
        },
      };
    }

    default:
      return state;
  }
}

// --- hook ---

export function useRemotex({ token = '', remember = true, initialHosts } = {}) {
  const [state, dispatch] = useReducer(reducer, {
    ...initialState,
    userToken: token,
    rememberToken: Boolean(remember),
    hosts: initialHosts || [],
  });

  // Mutable plumbing — reducer state is derived output, these are the
  // I/O handles that outlive renders.
  const apiRef = useRef(new RelayClient(state.userToken));
  const socketRef = useRef(null);
  // Non-null while a history batch is streaming in: replayed items collect
  // here and commit as ONE dispatch on history-end / history-chunk-end.
  const historyBufRef = useRef(null);
  const reconnectRef = useRef(null);
  const reconnectAttemptRef = useRef(0);
  const userClosedRef = useRef(false);
  // The session whose prompt queues are safe to persist. Set when the
  // relay says "attached" — before that the queues are empty because the
  // session just reset, and persisting that emptiness would wipe the very
  // backup we are about to restore.
  const promptBackupSessionRef = useRef(null);
  // Latest sendTurn inputs (model/effort/perms/images) — read lazily so
  // sendTurn doesn't invalidate on every picker tweak.
  const latestInputsRef = useRef({});
  latestInputsRef.current = {
    model: state.model,
    effort: state.effort,
    permissions: state.permissions,
    pendingImages: state.pendingImages,
    pendingApprovals: state.pendingApprovals,
    pendingUserInputs: state.pendingUserInputs,
    browsePath: state.browsePath,
    selectedHostId: state.selectedHostId,
    userToken: state.userToken,
    sessionId: state.session?.sessionId,
    goal: state.goal,
  };

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
    promptBackupSessionRef.current = null;
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
    promptBackupSessionRef.current = null;
    dispatch({ type: 'SESSION_RESET', status });
  }, []);

  // One writer for the sessionStorage backup, driven by the queues
  // themselves. Doing it here rather than at each dispatch site means two
  // prompts arriving in the same batch can't clobber each other's write.
  useEffect(() => {
    const sid = state.session?.sessionId;
    if (!sid || promptBackupSessionRef.current !== sid) return;
    writePromptBackup(sid, {
      approvals: state.pendingApprovals,
      userInputs: state.pendingUserInputs,
    });
  }, [state.session?.sessionId, state.pendingApprovals, state.pendingUserInputs]);

  const handleFrame = useCallback((frame) => {
    if (frame.type === 'attached') {
      reconnectAttemptRef.current = 0;
      const turnInFlight = attachedTurnInFlight(frame);
      if (turnInFlight !== null) {
        dispatch({ type: 'PENDING', pending: turnInFlight });
      }
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
        },
      });
      // Restoring the tab's own backup is a stop-gap: the relay's
      // pending-prompts frame lands a moment later and prunes anything a
      // peer answered while we were away.
      promptBackupSessionRef.current = frame.session_id;
      const backup = readPromptBackup(frame.session_id);
      if (backup && (backup.approvals.length || backup.userInputs.length)) {
        dispatch({
          type: 'PENDING_PROMPTS',
          approvals: backup.approvals,
          userInputs: backup.userInputs,
        });
      }
      return;
    }
    if (frame.type === 'pending-prompts') {
      dispatch({ type: 'PENDING_PROMPTS', ...normalizePromptSnapshot(frame) });
      return;
    }
    if (frame.type === 'replay-gap') {
      // Contract (C): the replay buffer evicted frames we asked for. Mark
      // the hole so a truncated transcript never reads as a complete one.
      const from = Number(frame.missed_from || 0);
      const to = Number(frame.missed_to || 0);
      dispatch({
        type: 'APPEND_EVENT',
        event: {
          id: `replay-gap-${frame.session_id || ''}-${from}-${to}`,
          role: 'gap',
          missedFrom: from,
          missedTo: to,
          completed: true,
        },
      });
      return;
    }
    if (frame.type === 'approval-resolved') {
      // Answered here or by a peer client — either way this prompt leaves
      // the queue and the next one becomes the head.
      dispatch({ type: 'APPROVAL_CLEAR', approvalId: frame.approval_id });
      return;
    }
    if (frame.type === 'user-input-resolved') {
      dispatch({ type: 'USER_INPUT_CLEAR', callId: frame.call_id });
      return;
    }
    if (frame.type === 'session-closed') {
      // Codex is gone; nothing can answer these any more.
      dispatch({ type: 'PENDING_PROMPTS', approvals: [], userInputs: [] });
      dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
      dispatch({ type: 'PENDING', pending: false });
      return;
    }
    if (frame.type === 'error') {
      dispatch({ type: 'SET_ERROR', error: frame.error || 'relay error' });
      if (frame.fatal) {
        // The relay will refuse this session id on every attempt (closed,
        // gone, or not ours). Retrying is an endless reconnect loop, so
        // stop: only a new session can make progress.
        userClosedRef.current = true;
        dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
        dispatch({ type: 'PENDING', pending: false });
      }
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
        dispatch({ type: 'SESSION_INFO', info: { model: data.model, cwd: data.cwd } });
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
        const stamp = (ev) => {
          if (!ev) return ev;
          ev.ts = Number.isFinite(data.ts) ? data.ts : Date.now() / 1000;
          return ev;
        };
        if (data.replayed && historyBufRef.current) {
          const ev = stamp(buildItemEvent(data));
          if (ev) historyBufRef.current.items.push(ev);
          return;
        }
        const ev = stamp(buildItemEvent(data));
        if (ev) {
          dispatch({
            type: 'APPEND_EVENT',
            event: ev,
            authoritative: data.resumed === true,
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
      case 'item-patch':
        // Progressive file-edit update. Codex resends the whole patch each
        // time, so this replaces the item's diff instead of appending.
        dispatch({
          type: 'COMPLETE_EVENT',
          id: data.item_id,
          patch: { output: data.output || '', command: data.args?.command || '' },
          keepPending: true,
        });
        return;
      case 'steer-failed':
        // The turn is still running — surface the failure without ending it.
        dispatch({
          type: 'SET_ERROR',
          error: data.error || 'could not steer this turn',
        });
        return;
      case 'item-completed': {
        // Replayed completions mirror their item-started payloads exactly
        // (see daemon _emit_history_turn) — nothing to patch while buffering.
        if (data.replayed && historyBufRef.current) return;
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
        return;
      }
      case 'turn-completed':
        // The relay drops every outstanding prompt for the session when a
        // turn ends, so both queues empty together.
        dispatch({ type: 'PENDING_PROMPTS', approvals: [], userInputs: [] });
        dispatch({ type: 'PENDING', pending: false });
        if (data.error) dispatch({ type: 'SET_ERROR', error: data.error });
        return;
      case 'approval-request':
        {
          const prompt = normalizeApprovalPrompt(data);
          if (!prompt) return;
          dispatch({ type: 'APPROVAL_REQUEST', prompt });
        }
        return;
      case 'user-input-request':
        {
          const prompt = normalizeUserInputPrompt(data);
          if (!prompt) return;
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
        appendSlashAckEvent(dispatch, cmd, ok, data);
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
      case 'goal-snapshot':
        dispatch({ type: 'GOAL_SET', goal: normalizeGoal(data.goal) });
        return;
      case 'goal-updated':
        dispatch({ type: 'GOAL_SET', goal: normalizeGoal(data.goal) });
        return;
      case 'goal-cleared':
        dispatch({ type: 'GOAL_CLEAR' });
        return;
      case 'turn-started':
        dispatch({ type: 'PENDING', pending: true });
        return;
      case 'history-begin':
        historyBufRef.current = { items: [], prepend: false };
        return;
      case 'history-chunk-begin':
        historyBufRef.current = { items: [], prepend: true };
        return;
      case 'history-end':
      case 'history-chunk-end': {
        const buf = historyBufRef.current;
        historyBufRef.current = null;
        dispatch({
          type: 'HISTORY_COMMIT',
          events: buf?.items || [],
          prepend: !!buf?.prepend,
          oldest: Number.isFinite(data.oldest) ? data.oldest : 0,
          hasMore: !!data.has_more,
        });
        return;
      }
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
              dispatch({ type: 'PENDING', pending: false });
              dispatch({ type: 'SESSION_STATUS', status: STATUS.Disconnected });
              return;
            }
            // Preserve the turn state during a transient transport drop.
            // The relay's next `attached.turn_in_flight` is authoritative;
            // clearing here makes a live turn look idle when replay starts
            // after its old turn-started frame.
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

  const goToSession = useCallback(() => {
    dispatch({ type: 'SET_SCREEN', screen: SCREENS.Session });
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
    if (initialHosts === undefined) refreshHosts();
  }, [initialHosts, refreshHosts]);

  // Model list, most-specific source first (contract B): what the selected
  // host's codex actually offers → the hostless default → the fallback
  // constant already sitting in state. Re-runs when the host changes, so
  // switching hosts re-asks that host.
  useEffect(() => {
    let cancelled = false;
    const hostId = state.selectedHostId;
    const apply = (models) => {
      if (cancelled || !Array.isArray(models) || models.length === 0) return false;
      dispatch({ type: 'MODEL_OPTIONS', options: models });
      return true;
    };
    (async () => {
      if (hostId) {
        try {
          const r = await apiRef.current.listHostModels(hostId);
          if (apply(r.models)) return;
        } catch {
          // Host offline / old relay without the route — fall through.
        }
      }
      try {
        if (apply(await apiRef.current.listModels())) return;
      } catch {
        // Silent: the embedded fallback list is already in state.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [state.selectedHostId]);

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
          dispatch({ type: 'TELEMETRY', hostId, data: snap.data, history: snap.history });
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
      const browsePath = hostHomePath(host);
      dispatch({ type: 'SELECT_HOST', id: host.id, browsePath });
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
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
      dispatch({ type: 'SELECT_HOST', id: firstOnline.id, browsePath: hostHomePath(firstOnline) });
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
    // Contract (A): reject oversize locally with a readable message
    // instead of shipping bytes the relay will refuse mid-stream.
    if (file && file.size > MAX_FILE_BYTES) {
      throw new Error(
        `${file.name} is ${formatBytes(file.size)} — the upload limit is ${formatBytes(MAX_FILE_BYTES)}`,
      );
    }
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
      // Instant paint: if a hover/press prefetch cached this thread's tail,
      // show it right away. The rows use `preview_` ids; HISTORY_COMMIT
      // drops them when the authoritative tail arrives.
      if (threadId) {
        const cached = getCachedPreview(hostId, threadId);
        cached?.forEach((turn, i) => {
          if (!turn?.text) return;
          dispatch({
            type: 'APPEND_EVENT',
            event: {
              id: `preview_${threadId}_${i}`,
              role: turn.role === 'user' ? 'user' : 'agent',
              text: turn.text,
              completed: true,
            },
          });
        });
      }
      try {
        const sid = await apiRef.current.openSession(hostId, { threadId, cwd });
        dispatch({
          type: 'SESSION_ATTACHED',
          session: { sessionId: sid, hostId, cwd: cwd || null },
        });
        // Replay from the very start. openSession just reset events to [], and
        // the session may be REUSED (still live on the relay) — in which case
        // this browser already holds a high stored last_seq for it. Without
        // replayFromStart the relay would replay nothing past that cursor and
        // the chat would render empty. (Reconnect paths intentionally keep the
        // stored cursor, since they don't reset events.)
        attachSocket(sid, { replayFromStart: true });
      } catch (t) {
        dispatch({ type: 'SESSION_STATUS', status: STATUS.Error });
        dispatch({ type: 'SET_ERROR', error: t.message });
      }
    },
    [attachSocket, detachSession],
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
    appendSlashUserEvent(dispatch, cmd, args);
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
          appendSlashUserEvent(dispatch, slash.cmd, slash.args);
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

  // Send into the running turn instead of ending it. The relay echoes the
  // message to every attached client, so we don't append it locally.
  // Ask the daemon for the next page of older turns. Guards live here so
  // EventStream's sentinel can fire blindly.
  const loadOlderHistory = useCallback(() => {
    if (!state.historyHasMore || state.historyLoading) return;
    const sock = socketRef.current;
    if (!sock?.isOpen?.()) return;
    dispatch({ type: 'HISTORY_LOADING' });
    sock.sendHistoryMore(state.historyOldest, 10);
  }, [state.historyHasMore, state.historyLoading, state.historyOldest]);

  // Hover/press prefetch for a saved-thread row. Cheap server-side (disk
  // + LRU on the daemon, never codex), deduped and capped client-side.
  const prefetchThreadPreview = useCallback((thread) => {
    if (!thread?.id) return;
    const hostId = thread.host_id || latestInputsRef.current.selectedHostId;
    prefetchPreview(apiRef.current, hostId, thread.id);
  }, []);

  const steerTurn = useCallback((rawText) => {
    const input = (rawText || '').trim();
    const { pendingImages } = latestInputsRef.current;
    if (!input && pendingImages.length === 0) return;
    const sock = socketRef.current;
    if (!sock) return;
    const sent = sock.sendSteer({
      input,
      images: pendingImages.map((a) => ({ mime: a.mime, data: a.base64 })),
    });
    if (!sent) {
      dispatch({ type: 'SET_ERROR', error: 'socket is not connected' });
      return;
    }
    dispatch({ type: 'CLEAR_IMAGES' });
  }, []);

  const interruptTurn = useCallback(() => {
    socketRef.current?.sendInterrupt();
  }, []);

  // Answers the head of the approval queue; popping it reveals the next.
  // If the relay can't deliver the answer it re-pushes a pending-prompts
  // snapshot, which puts the prompt back.
  const resolveApproval = useCallback((decision) => {
    const pending = latestInputsRef.current.pendingApprovals[0];
    if (!pending) return;
    if (socketRef.current?.sendApproval(pending.approvalId, decision)) {
      dispatch({ type: 'APPROVAL_CLEAR', approvalId: pending.approvalId });
    }
  }, []);

  // answers: { <question_id>: [string, ...] }
  const resolveUserInput = useCallback((answers) => {
    const pending = latestInputsRef.current.pendingUserInputs[0];
    if (!pending) return;
    if (socketRef.current?.sendUserInput(pending.callId, answers || {})) {
      dispatch({ type: 'USER_INPUT_CLEAR', callId: pending.callId });
    }
  }, []);
  const cancelUserInput = useCallback(() => {
    const pending = latestInputsRef.current.pendingUserInputs[0];
    if (!pending) return;
    // Empty answers map → daemon returns { answers: {} } and codex
    // treats every question as "skipped".
    if (socketRef.current?.sendUserInput(pending.callId, {})) {
      dispatch({ type: 'USER_INPUT_CLEAR', callId: pending.callId });
    }
  }, []);

  const attachImage = useCallback(async (file) => {
    try {
      // Contract (A): one ceiling, checked before we spend memory on the
      // base64 copy and before the relay would reject (or a too-large WS
      // frame would kill the socket). Images ride inside a single
      // turn-start frame, so the whole batch has to fit.
      const already = latestInputsRef.current.pendingImages.reduce(
        (n, img) => n + (img.bytes || 0),
        0,
      );
      if (already + file.size > MAX_FILE_BYTES) {
        dispatch({
          type: 'SET_ERROR',
          error: `image: ${formatBytes(file.size)} exceeds the ${formatBytes(MAX_FILE_BYTES)} attachment limit`,
        });
        return;
      }
      const base64 = await readAsBase64(file);
      const dataUrl = `data:${file.type};base64,${base64}`;
      dispatch({
        type: 'ATTACH_IMAGE',
        image: {
          dataUrl,
          mime: file.type,
          base64,
          bytes: file.size,
          label: file.name.slice(-32),
        },
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
    promptBackupSessionRef.current = null;
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
  // browsing a directory) reflects into window.location, and any incoming
  // URL (initial load, bookmark, browser back/forward) replays through the
  // navigation callbacks above.
  //
  // pushState is used only when the screen name changes (that's a real
  // navigation the user should be able to back-button out of). Intra-screen
  // changes — cd'ing the file browser — use replaceState so they don't spam
  // the back-button stack.
  const urlReadyRef = useRef(false);
  const lastScreenRef = useRef(null);
  const applyRouteRef = useRef(null);

  applyRouteRef.current = (route) => {
    if (route.screen === SCREENS.Hosts) {
      dispatch({ type: 'SET_SCREEN', screen: SCREENS.Hosts });
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
  ]);

  // --- public surface ---

  return useMemo(
    () => ({
      state,
      setModel: (m) => dispatch({ type: 'SET_MODEL', model: m }),
      setEffort: (e) => dispatch({ type: 'SET_EFFORT', effort: e }),
      setPermissions: (p) => dispatch({ type: 'SET_PERMS', permissions: p }),
      clearFeedback: () => dispatch({ type: 'CLEAR_FEEDBACK' }),
      clearError: () => dispatch({ type: 'SET_ERROR', error: null }),
      goToHosts,
      goToDashboard,
      goToSession,
      goToFiles,
      refreshHosts,
      openHost,
      refreshThreads,
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
      startSessionInCurrentPath: () =>
        openSession({ cwd: latestInputsRef.current.browsePath || null }),
      closeSession,
      sendTurn,
      sendSlash,
      interruptTurn,
      steerTurn,
      loadOlderHistory,
      prefetchThreadPreview,
      resolveApproval,
      resolveUserInput,
      cancelUserInput,
      attachImage,
      removeImage,
      // Internal escape hatch: WorkspaceFilesDrawer needs apiRef directly
      // so a single component can call read/rename/delete/upload without
      // dragging four wrappers through props.
      apiRef,
    }),
    [
      state,
      goToHosts,
      goToDashboard,
      goToSession,
      goToFiles,
      refreshHosts,
      openHost,
      refreshThreads,
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
      closeSession,
      sendTurn,
      sendSlash,
      interruptTurn,
      steerTurn,
      loadOlderHistory,
      prefetchThreadPreview,
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
      // Codex has item types we don't render specially — contextCompaction,
      // webSearch, plan, sleep, subAgentActivity, enteredReviewMode… Show a
      // readable name rather than a raw camelCase identifier.
      return { id, role: 'system', label: humanizeItemType(data.item_type), detail: '' };
  }
}

function humanizeItemType(type) {
  if (!type) return 'item';
  return type
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .toLowerCase();
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
