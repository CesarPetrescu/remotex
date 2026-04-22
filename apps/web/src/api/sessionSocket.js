// Thin wrapper around the browser WebSocket. Emits parsed JSON frames
// to `onFrame`, signals lifecycle via `onStatus`, and exposes typed
// senders that match the daemon's expected frame shapes. The caller
// owns the lifecycle via `close()`; reconnect logic lives in the
// session hook, not here — same split as Android.

export class SessionSocket {
  constructor({ userToken, sessionId, onFrame, onStatus }) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws/client`;
    this.ws = new WebSocket(url);
    this.onFrame = onFrame || (() => {});
    this.onStatus = onStatus || (() => {});

    this.ws.addEventListener('open', () => {
      this.onStatus('connecting');
      this.send({ type: 'hello', token: userToken, session_id: sessionId });
    });
    this.ws.addEventListener('message', (ev) => {
      let frame;
      try {
        frame = JSON.parse(ev.data);
      } catch {
        return;
      }
      this.onFrame(frame);
    });
    this.ws.addEventListener('close', () => this.onStatus('disconnected'));
    this.ws.addEventListener('error', () => this.onStatus('error'));
  }

  send(obj) {
    if (this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify(obj));
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

  close() {
    try {
      this.ws.close(1000, 'client-closed');
    } catch {
      // already closed
    }
  }
}
