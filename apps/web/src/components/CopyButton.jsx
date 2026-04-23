import { useState } from 'react';
import { copyText } from '../util/copy';

// Small "copy" affordance parked in the top-right corner of a code block.
// Parent needs position: relative (md-code already is; item-code gets it
// from styles.css).
export function CopyButton({ getText, className = '' }) {
  const [state, setState] = useState('idle'); // 'idle' | 'copied' | 'failed'
  async function onClick(e) {
    e.stopPropagation();
    const text = typeof getText === 'function' ? getText() : getText;
    const ok = await copyText(text || '');
    setState(ok ? 'copied' : 'failed');
    setTimeout(() => setState('idle'), 1200);
  }
  const label = state === 'copied' ? 'copied' : state === 'failed' ? 'failed' : 'copy';
  return (
    <button
      type="button"
      className={`copy-btn ${state} ${className}`}
      onClick={onClick}
      aria-label="Copy to clipboard"
      title="Copy to clipboard"
    >
      {label}
    </button>
  );
}
