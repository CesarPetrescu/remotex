import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

// Submit control: always exactly ONE primary button, in the same place.
//
//   idle            → arrow, fires turn-start
//   turn running    → arrow means "steer": the text goes into the live turn
//   + text typed      (codex turn/steer). A chevron opens the alternative,
//                     "queue as next turn".
//
// Stop is deliberately NOT here. It acts on the running turn, not on your
// draft, so it lives on the "Working…" row in the transcript — which also
// keeps a red button from sitting where your thumb aims for send.
//
// The menu borrows the `.dd-*` classes from the composer chips, so it docks
// as a bottom sheet on phones for free.
export function SendOrStopButton({
  pending,
  canSend,
  canSteer,
  canQueue,
  onSend,
  onSteer,
  onQueue,
}) {
  const steering = pending && canSteer;
  const enabled = steering || canSend;
  const primary = steering ? onSteer : onSend;

  return (
    <div className="turn-actions">
      <button
        type="button"
        className={`send-stop send ${enabled ? 'ready' : ''}${steering ? ' steer' : ''}`}
        disabled={!enabled}
        onClick={primary}
        aria-label={steering ? 'Steer current turn' : 'Send'}
        title={steering ? 'Add to the running turn now' : 'Send'}
      >
        ↑
      </button>
      {steering && canQueue && <AltMenu onQueue={onQueue} onSteer={onSteer} />}
    </div>
  );
}

// The chevron half of the split button. Only exists while a turn is running,
// which is the only time there's a second choice to make.
function AltMenu({ onQueue, onSteer }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const ref = useRef(null);
  const menuRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    function onDown(e) {
      if (ref.current?.contains(e.target)) return;
      if (menuRef.current?.contains(e.target)) return;
      setOpen(false);
    }
    function onKey(e) {
      if (e.key === 'Escape') setOpen(false);
    }
    function onReflow(e) {
      if (e.target === menuRef.current) return;
      setOpen(false);
    }
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('resize', onReflow);
    window.addEventListener('scroll', onReflow, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', onReflow);
      window.removeEventListener('scroll', onReflow, true);
    };
  }, [open]);

  function toggle() {
    if (!open && ref.current) {
      const r = ref.current.getBoundingClientRect();
      const width = 208;
      // Right-aligned to the button: the composer sits at the window edge, so
      // a left-anchored menu would run off it.
      const left = Math.max(8, Math.min(r.right - width, window.innerWidth - width - 8));
      setPos({ left, width, bottom: window.innerHeight - r.top + 6 });
    }
    setOpen((o) => !o);
  }

  const choose = (fn) => () => {
    setOpen(false);
    fn();
  };

  return (
    <div ref={ref} className="send-alt-wrap">
      <button
        type="button"
        className="send-stop send-alt ready"
        onClick={toggle}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label="Other send options"
        title="Other send options"
      >
        ⌄
      </button>
      {open && pos && createPortal(
        <>
          <div className="dd-scrim" onClick={() => setOpen(false)} aria-hidden="true" />
          <div
            ref={menuRef}
            className="dd dd-portal"
            role="listbox"
            aria-label="Send options"
            style={{ left: pos.left, width: pos.width, bottom: pos.bottom }}
          >
            <div className="dd-head">
              <span className="dd-head-label">this message</span>
              <button
                type="button"
                className="dd-close"
                onClick={() => setOpen(false)}
                aria-label="Close"
              >
                ×
              </button>
            </div>
            <button
              type="button"
              role="option"
              aria-selected="true"
              className="dd-item selected"
              onClick={choose(onSteer)}
            >
              <span className="dd-check" aria-hidden="true">✓</span>
              <span className="dd-item-main">
                <span className="dd-line">Steer now</span>
                <span className="dd-hint">add it to the running turn</span>
              </span>
            </button>
            <button
              type="button"
              role="option"
              aria-selected="false"
              className="dd-item"
              onClick={choose(onQueue)}
            >
              <span className="dd-check" aria-hidden="true" />
              <span className="dd-item-main">
                <span className="dd-line">Queue as next turn</span>
                <span className="dd-hint">runs when this turn finishes</span>
              </span>
            </button>
          </div>
        </>,
        document.body,
      )}
    </div>
  );
}
