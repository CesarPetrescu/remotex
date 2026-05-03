// Thin wrapper around the browser WebSocket. Emits parsed JSON frames
// to `onFrame`, signals lifecycle via `onStatus`, and exposes typed
// senders that match the daemon's expected frame shapes. The caller
// owns the lifecycle via `close()`; reconnect logic lives in the
// session hook, not here — same split as Android.

const HEARTBEAT_INTERVAL_MS = 20000;
const HEARTBEAT_STALE_MS = 70000;
const CLIENT_ID_KEY = 'remotex.webClientId';
const LAST_SEQ_PREFIX = 'remotex.lastSeq.';

function loadClientId() {
  try {
    const existing = sessionStorage.getItem(CLIENT_ID_KEY);
    if (existing) return existing;
    const id = `web-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`;
    sessionStorage.setItem(CLIENT_ID_KEY, id);
    return id;
  } catch {
    return `web-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`;
  }
}

function loadLastSeq(sessionId) {
  try {
    const raw = sessionStorage.getItem(`${LAST_SEQ_PREFIX}${sessionId}`);
    const parsed = Number.parseInt(raw || '0', 10);
    return Number.isFinite(parsed) ? parsed : 0;
  } catch {
    return 0;
  }
}

function storeLastSeq(sessionId, seq) {
  if (!Number.isFinite(seq)) return;
  try {
    sessionStorage.setItem(`${LAST_SEQ_PREFIX}${sessionId}`, String(seq));
  } catch {
    // ignore
  }
}

export class SessionSocket {
  constructor({ userToken, sessionId, onFrame, onStatus, lastSeq = null }) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws/client`;
    this.ws = new WebSocket(url);
    this.sessionId = sessionId;
    this.clientId = loadClientId();
    this.onFrame = onFrame || (() => {});
    this.onStatus = onStatus || (() => {});
    this.lastMessageAt = Date.now();
    this.heartbeat = null;
    this.closedByCaller = false;
    this.requestedLastSeq = Number.isFinite(lastSeq) ? lastSeq : loadLastSeq(sessionId);

    this.ws.addEventListener('open', () => {
      this.onStatus('connecting');
      this.send({
        type: 'hello',
        token: userToken,
        session_id: sessionId,
        client_id: this.clientId,
        client_name: 'web',
        last_seq: this.requestedLastSeq,
      });
      this.startHeartbeat();
    });
    this.ws.addEventListener('message', (ev) => {
      this.lastMessageAt = Date.now();
      let frame;
      try {
        frame = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (frame.type === 'pong') return;
      if (frame.type === 'attached') {
        frame.replay_from = this.requestedLastSeq;
      }
      if (Number.isFinite(frame.seq)) {
        storeLastSeq(this.sessionId, frame.seq);
      }
      this.onFrame(frame);
    });
    this.ws.addEventListener('close', () => {
      this.stopHeartbeat();
      this.onStatus('disconnected');
    });
    this.ws.addEventListener('error', () => this.onStatus('error'));
  }

  send(obj) {
    if (this.ws.readyState !== WebSocket.OPEN) return false;
    this.ws.send(JSON.stringify(obj));
    return true;
  }

  isOpen() {
    return this.ws.readyState === WebSocket.OPEN;
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeat = window.setInterval(() => {
      if (this.ws.readyState !== WebSocket.OPEN) return;
      if (Date.now() - this.lastMessageAt > HEARTBEAT_STALE_MS) {
        this.ws.close(4000, 'heartbeat-timeout');
        return;
      }
      this.send({ type: 'ping', ts: Date.now() });
    }, HEARTBEAT_INTERVAL_MS);
  }

  stopHeartbeat() {
    if (this.heartbeat) {
      window.clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }

  sendTurn({ input, model, effort, permissions, images }) {
    const frame = {
      type: 'turn-start',
      input,
      client_message_id: `msg-${Math.random().toString(36).slice(2, 12)}`,
    };
    if (model) frame.model = model;
    if (effort && effort !== 'none') frame.effort = effort;
    if (permissions) frame.permissions = permissions;
    if (images?.length) frame.images = images;
    return this.send(frame);
  }

  sendInterrupt() {
    return this.send({ type: 'turn-interrupt' });
  }

  sendSlash(cmd, args) {
    const frame = { type: 'slash-command', command: cmd };
    if (args) frame.args = args;
    return this.send(frame);
  }

  sendApproval(approvalId, decision) {
    return this.send({ type: 'approval-response', approval_id: approvalId, decision });
  }

  // Reply to codex's request_user_input prompt. `answers` is shaped
  //   { <question_id>: ["selected label", "freeform notes"] }
  // The daemon normalizes either flat-array or {answers:[]} on receive.
  sendUserInput(callId, answers) {
    return this.send({ type: 'user-input-response', call_id: callId, answers });
  }

  close({ endSession = false } = {}) {
    this.closedByCaller = true;
    try {
      if (endSession && this.ws.readyState === WebSocket.OPEN) {
        this.send({ type: 'session-close' });
      }
      this.ws.close(1000, 'client-closed');
    } catch {
      // already closed
    } finally {
      this.stopHeartbeat();
    }
  }
}
