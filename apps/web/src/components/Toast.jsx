import { useEffect } from 'react';
import { createPortal } from 'react-dom';

// Bottom-right transient toast. Two flavours — error (red border,
// stays until dismissed) and info (neutral border, auto-fades after
// `durationMs`). Same shape as Android's error/slash-feedback banner.
//
// Rendered via portal to document.body so the dashboard's grid
// (`.dashboard-layout > * { position: relative }`) can't override the
// toast's `position: fixed` and dump it into a stray grid cell at the
// bottom-left of the screen.
export function Toast({ message, onDismiss, tone = 'info', durationMs = 3500 }) {
  useEffect(() => {
    if (!message || tone === 'error') return undefined;
    const t = setTimeout(onDismiss, durationMs);
    return () => clearTimeout(t);
  }, [message, onDismiss, durationMs, tone]);

  if (!message) return null;
  const node = (
    <div className={`toast ${tone}`} onClick={onDismiss}>
      {message}
    </div>
  );
  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}
