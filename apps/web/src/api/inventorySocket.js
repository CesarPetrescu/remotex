const HEARTBEAT_INTERVAL_MS = 20000;
const HEARTBEAT_STALE_MS = 70000;
const READY_TIMEOUT_MS = 15000;

// Deliberately in-memory, not sessionStorage. Browsers clone sessionStorage
// when a tab is duplicated; reusing that id would make the two tabs replace
// each other's relay socket forever. A module-lifetime id is stable across
// reconnects in this tab and unique in a duplicated tab.
const INVENTORY_CLIENT_ID = `inventory-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`;

// Long-lived, authenticated notification channel for host/thread inventory.
// Reconnect policy belongs to useRemotex; this wrapper only owns one socket.
export class InventorySocket {
  constructor({ userToken, onFrame, onStatus }) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${proto}//${location.host}/ws/inventory`);
    this.clientId = INVENTORY_CLIENT_ID;
    this.onFrame = onFrame || (() => {});
    this.onStatus = onStatus || (() => {});
    this.lastMessageAt = Date.now();
    this.heartbeat = null;
    this.readyTimeout = null;
    this.fatal = false;

    this.startReadyTimeout();

    this.ws.addEventListener('open', () => {
      this.lastMessageAt = Date.now();
      this.startReadyTimeout();
      this.onStatus('connecting');
      this.send({
        type: 'hello',
        token: userToken,
        client_id: this.clientId,
        client_name: 'web',
      });
      this.startHeartbeat();
    });
    this.ws.addEventListener('message', (event) => {
      this.lastMessageAt = Date.now();
      let frame;
      try {
        frame = JSON.parse(event.data);
      } catch {
        return;
      }
      if (frame.type === 'pong') return;
      if (frame.type === 'inventory-ready') {
        this.stopReadyTimeout();
        this.onStatus('connected');
      }
      if (frame.type === 'error' && frame.fatal) {
        this.fatal = true;
        this.stopReadyTimeout();
        this.onStatus('fatal');
      }
      this.onFrame(frame);
    });
    this.ws.addEventListener('close', (event) => {
      this.stopHeartbeat();
      this.stopReadyTimeout();
      if (this.fatal || event.code === 4401) {
        if (!this.fatal) this.onStatus('fatal');
        this.fatal = true;
        return;
      }
      this.onStatus('disconnected');
    });
    this.ws.addEventListener('error', () => {
      if (!this.fatal) this.onStatus('error');
    });
  }

  send(frame) {
    if (this.ws.readyState !== WebSocket.OPEN) return false;
    this.ws.send(JSON.stringify(frame));
    return true;
  }

  isActive() {
    return this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN;
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

  startReadyTimeout() {
    this.stopReadyTimeout();
    this.readyTimeout = window.setTimeout(() => {
      if (
        this.ws.readyState === WebSocket.CONNECTING
        || this.ws.readyState === WebSocket.OPEN
      ) {
        this.ws.close(4000, 'inventory-ready-timeout');
      }
    }, READY_TIMEOUT_MS);
  }

  stopReadyTimeout() {
    if (this.readyTimeout) {
      window.clearTimeout(this.readyTimeout);
      this.readyTimeout = null;
    }
  }

  close() {
    try {
      this.ws.close(1000, 'client-closed');
    } catch {
      // already closed
    } finally {
      this.stopHeartbeat();
      this.stopReadyTimeout();
    }
  }
}
