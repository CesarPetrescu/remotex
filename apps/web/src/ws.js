export function openSessionSocket({ token, sessionId, onFrame, onStatus }) {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${proto}//${location.host}/ws/client`;
  const ws = new WebSocket(url);

  ws.addEventListener('open', () => {
    onStatus?.('connecting');
    ws.send(JSON.stringify({ type: 'hello', token, session_id: sessionId }));
  });

  ws.addEventListener('message', (ev) => {
    let frame;
    try {
      frame = JSON.parse(ev.data);
    } catch {
      return;
    }
    onFrame?.(frame);
  });

  ws.addEventListener('close', () => onStatus?.('disconnected'));
  ws.addEventListener('error', () => onStatus?.('error'));

  return {
    sendTurn(input) {
      ws.send(JSON.stringify({ type: 'turn-start', input }));
    },
    close() {
      ws.close();
    },
  };
}
