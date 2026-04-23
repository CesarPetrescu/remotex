// Thin wrapper around the browser WebSocket. Emits parsed JSON frames
// to `onFrame`, signals lifecycle via `onStatus`, and exposes typed
// senders that match the daemon's expected frame shapes. The caller
// owns the lifecycle via `close()`; reconnect logic lives in the
// session hook, not here — same split as Android.

const HEARTBEAT_INTERVAL_MS = 20000;
const HEARTBEAT_STALE_MS = 70000;

export class SessionSocket {
  constructor({ userToken, sessionId, onFrame, onStatus }) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws/client`;
    this.ws = new WebSocket(url);
    this.sessionId = sessionId;
    this.onFrame = onFrame || (() => {});
    this.onStatus = onStatus || (() => {});
    this.lastMessageAt = Date.now();
    this.heartbeat = null;
    this.closedByCaller = false;

    this.ws.addEventListener('open', () => {
      this.onStatus('connecting');
      this.send({ type: 'hello', token: userToken, session_id: sessionId });
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
    const frame = { type: 'turn-start', input };
    if (model) frame.model = model;
    if (effort && effort !== 'none') frame.effort = effort;
    if (permissions) frame.permissions = permissions;
    if (images?.length) frame.images = images;
    this.send(frame);
  }

  sendInterrupt() {
    this.send({ type: 'turn-interrupt' });
  }

  sendSlash(cmd, args) {
    const frame = { type: 'slash-command', command: cmd };
    if (args) frame.args = args;
    this.send(frame);
  }

  sendApproval(approvalId, decision) {
    this.send({ type: 'approval-response', approval_id: approvalId, decision });
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
