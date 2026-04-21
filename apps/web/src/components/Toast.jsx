import { useEffect } from 'react';

export default function Toast({ message, onDismiss, durationMs = 4000 }) {
  useEffect(() => {
    if (!message) return;
    const t = setTimeout(onDismiss, durationMs);
    return () => clearTimeout(t);
  }, [message, onDismiss, durationMs]);

  if (!message) return null;
  return <div className="toast">{message}</div>;
}
