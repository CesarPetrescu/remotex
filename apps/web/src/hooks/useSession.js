import { useCallback, useEffect, useRef, useState } from 'react';
import { openSessionSocket } from '../ws.js';

// State machine for one Codex session end-to-end.
// Owns the WebSocket, turns relay frames into a flat event list the
// EventStream can render, and exposes a sendTurn callback.
export function useSession({ api, token, hostId }) {
  const [status, setStatus] = useState('idle');
  const [events, setEvents] = useState([]);
  const [sessionInfo, setSessionInfo] = useState(null);
  const [error, setError] = useState(null);
  const [pending, setPending] = useState(false);
  const socketRef = useRef(null);

  const close = useCallback(() => {
    socketRef.current?.close();
    socketRef.current = null;
    setStatus('idle');
    setEvents([]);
    setSessionInfo(null);
    setPending(false);
  }, []);

  useEffect(() => () => socketRef.current?.close(), []);

  const open = useCallback(async () => {
    setError(null);
    setEvents([]);
    setStatus('opening');
    try {
      const sessionId = await api.openSession(hostId);
      setSessionInfo({ sessionId, hostId });
      const sock = openSessionSocket({
        token,
        sessionId,
        onStatus: (s) => setStatus(s),
        onFrame: (frame) => handleFrame(frame, setEvents, setSessionInfo, setStatus, setPending),
      });
      socketRef.current = sock;
      setStatus('connecting');
    } catch (err) {
      setError(err.message);
      setStatus('idle');
    }
  }, [api, token, hostId]);

  const sendTurn = useCallback(
    (input) => {
      if (!socketRef.current) return;
      setPending(true);
      setEvents((prev) => [...prev, { kind: 'user', id: `u-${Date.now()}`, text: input }]);
      socketRef.current.sendTurn(input);
    },
    []
  );

  return { status, events, sessionInfo, error, pending, open, close, sendTurn };
}

function handleFrame(frame, setEvents, setSessionInfo, setStatus, setPending) {
  if (frame.type === 'attached') {
    setStatus('connected');
    setSessionInfo((prev) => ({ ...(prev || {}), sessionId: frame.session_id, hostId: frame.host_id }));
    return;
  }
  if (frame.type === 'session-closed') {
    setStatus('closed');
    setPending(false);
    return;
  }
  if (frame.type === 'error') {
    setStatus('error');
    return;
  }
  if (frame.type !== 'session-event') return;

  const ev = frame.event || {};
  const data = ev.data || {};
  const kind = ev.kind;

  if (kind === 'session-started') {
    setSessionInfo((prev) => ({ ...(prev || {}), model: data.model, cwd: data.cwd }));
    return;
  }
  if (kind === 'turn-started') {
    return;
  }
  if (kind === 'item-started') {
    setEvents((prev) => [
      ...prev,
      { kind: 'item', id: data.item_id, itemType: data.item_type, tool: data.tool, args: data.args, text: '' },
    ]);
    return;
  }
  if (kind === 'item-delta') {
    setEvents((prev) =>
      prev.map((e) => (e.id === data.item_id ? { ...e, text: (e.text || '') + (data.delta || '') } : e))
    );
    return;
  }
  if (kind === 'item-completed') {
    setEvents((prev) =>
      prev.map((e) =>
        e.id === data.item_id
          ? {
              ...e,
              completed: true,
              text: data.item_type === 'agent_reasoning' ? data.text || '' : e.text,
              output: data.output,
            }
          : e
      )
    );
    return;
  }
  if (kind === 'turn-completed') {
    setPending(false);
    return;
  }
}
