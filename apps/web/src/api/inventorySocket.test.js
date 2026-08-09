import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InventorySocket } from './inventorySocket';

class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.listeners = new Map();
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  emit(type, event = {}) {
    for (const listener of this.listeners.get(type) || []) listener(event);
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.emit('open');
  }

  message(frame) {
    this.emit('message', { data: JSON.stringify(frame) });
  }

  send(payload) {
    this.sent.push(JSON.parse(payload));
  }

  close(code = 1000, reason = '') {
    this.readyState = FakeWebSocket.CLOSED;
    this.closeCode = code;
    this.closeReason = reason;
  }

  serverClose(code = 1000) {
    this.readyState = FakeWebSocket.CLOSED;
    this.emit('close', { code });
  }
}

describe('InventorySocket', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal('location', { protocol: 'https:', host: 'remotex.example' });
    vi.stubGlobal('window', {
      setInterval: (...args) => globalThis.setInterval(...args),
      clearInterval: (...args) => globalThis.clearInterval(...args),
      setTimeout: (...args) => globalThis.setTimeout(...args),
      clearTimeout: (...args) => globalThis.clearTimeout(...args),
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('authenticates with a stable client id without putting the token in the URL', () => {
    const first = new InventorySocket({ userToken: 'secret-token' });
    const firstWebSocket = FakeWebSocket.instances[0];
    firstWebSocket.open();

    expect(firstWebSocket.url).toBe('wss://remotex.example/ws/inventory');
    expect(firstWebSocket.url).not.toContain('secret-token');
    expect(firstWebSocket.sent[0]).toMatchObject({
      type: 'hello',
      token: 'secret-token',
      client_name: 'web',
    });

    const second = new InventorySocket({ userToken: 'secret-token' });
    FakeWebSocket.instances[1].open();
    expect(FakeWebSocket.instances[1].sent[0].client_id)
      .toBe(firstWebSocket.sent[0].client_id);

    first.close();
    second.close();
  });

  it('sends heartbeats and treats pong as liveness rather than inventory', () => {
    const onFrame = vi.fn();
    new InventorySocket({ userToken: 'secret-token', onFrame });
    const webSocket = FakeWebSocket.instances[0];
    webSocket.open();
    webSocket.message({ type: 'inventory-ready' });
    onFrame.mockClear();

    vi.advanceTimersByTime(20000);
    expect(webSocket.sent.at(-1)).toMatchObject({ type: 'ping' });

    vi.advanceTimersByTime(40000);
    webSocket.message({ type: 'pong' });
    vi.advanceTimersByTime(60000);

    expect(onFrame).not.toHaveBeenCalled();
    expect(webSocket.closeCode).toBeUndefined();
  });

  it('closes a socket that has been silent past the stale limit', () => {
    new InventorySocket({ userToken: 'secret-token' });
    const webSocket = FakeWebSocket.instances[0];
    webSocket.open();
    webSocket.message({ type: 'inventory-ready' });

    vi.advanceTimersByTime(80000);

    expect(webSocket.closeCode).toBe(4000);
    expect(webSocket.closeReason).toBe('heartbeat-timeout');
  });

  it('bounds both the connecting and hello-ready phases', () => {
    new InventorySocket({ userToken: 'secret-token' });
    const connecting = FakeWebSocket.instances[0];

    vi.advanceTimersByTime(15000);
    expect(connecting.closeReason).toBe('inventory-ready-timeout');

    new InventorySocket({ userToken: 'secret-token' });
    const awaitingReady = FakeWebSocket.instances[1];
    awaitingReady.open();
    vi.advanceTimersByTime(15000);
    expect(awaitingReady.closeReason).toBe('inventory-ready-timeout');
  });

  it('distinguishes ready, retryable close, and fatal authentication lifecycles', () => {
    const normalStatuses = [];
    new InventorySocket({
      userToken: 'secret-token',
      onStatus: (status) => normalStatuses.push(status),
    });
    const normalWebSocket = FakeWebSocket.instances[0];
    normalWebSocket.open();
    normalWebSocket.message({ type: 'inventory-ready' });
    normalWebSocket.serverClose();
    expect(normalStatuses).toEqual(['connecting', 'connected', 'disconnected']);

    const fatalStatuses = [];
    new InventorySocket({
      userToken: 'bad-token',
      onStatus: (status) => fatalStatuses.push(status),
    });
    const fatalWebSocket = FakeWebSocket.instances[1];
    fatalWebSocket.open();
    fatalWebSocket.message({ type: 'error', error: 'invalid token', fatal: true });
    fatalWebSocket.serverClose(4401);
    expect(fatalStatuses).toEqual(['connecting', 'fatal']);

    const rejectedStatuses = [];
    new InventorySocket({
      userToken: 'bad-token',
      onStatus: (status) => rejectedStatuses.push(status),
    });
    const rejectedWebSocket = FakeWebSocket.instances[2];
    rejectedWebSocket.open();
    rejectedWebSocket.serverClose(4401);
    expect(rejectedStatuses).toEqual(['connecting', 'fatal']);
  });
});
