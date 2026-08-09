// Send / steer / stop button.
//
//   idle            → amber up-arrow, fires turn-start
//   turn running    → red square, fires turn-interrupt
//   turn running    → up-arrow again as soon as you type: the text is
//   + text typed      steered into the live turn (codex turn/steer), so you
//                     don't have to interrupt and retype. Same flow as
//                     Android.
export function SendOrStopButton({ pending, canSend, canSteer, onSend, onSteer, onStop }) {
  const steering = pending && canSteer;
  const enabled = steering || pending || canSend;
  const label = steering ? 'Steer' : pending ? 'Stop' : 'Send';
  const cls = steering
    ? 'send-stop send ready steer'
    : pending
      ? 'send-stop stop'
      : `send-stop send ${canSend ? 'ready' : ''}`;
  return (
    <button
      type="button"
      className={cls}
      disabled={!enabled}
      onClick={steering ? onSteer : pending ? onStop : onSend}
      aria-label={label}
      title={steering ? 'Send into the running turn' : label}
    >
      {steering ? '↑' : pending ? '■' : '↑'}
    </button>
  );
}
