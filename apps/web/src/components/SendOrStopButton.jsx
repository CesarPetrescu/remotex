// Flip-state send / stop button. Amber up-arrow when ready to send,
// red square while a turn is in flight. Tapping stop fires the
// turn-interrupt frame which the daemon turns into codex
// turn/interrupt — same flow as Android.
export function SendOrStopButton({ pending, canSend, onSend, onStop }) {
  const enabled = pending || canSend;
  const label = pending ? 'Stop' : 'Send';
  const cls = pending ? 'send-stop stop' : `send-stop send ${canSend ? 'ready' : ''}`;
  return (
    <button
      type="button"
      className={cls}
      disabled={!enabled}
      onClick={pending ? onStop : onSend}
      aria-label={label}
    >
      {pending ? '■' : '↑'}
    </button>
  );
}
