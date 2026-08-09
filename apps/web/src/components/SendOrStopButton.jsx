// Send / queue / steer / stop controls.
//
//   idle            → amber up-arrow, fires turn-start
//   turn running    → red square, fires turn-interrupt
//   turn running    → up-arrow again as soon as you type: the text is
//   + text typed      steered into the live turn (codex turn/steer), so you
//                     don't have to interrupt and retype. Same flow as
//                     Android.
export function SendOrStopButton({
  pending,
  canSend,
  canSteer,
  canQueue,
  onSend,
  onSteer,
  onQueue,
  onStop,
}) {
  const steering = pending && canSteer;
  if (steering) {
    return (
      <div className="turn-actions">
        <button
          type="button"
          className="send-stop queue ready"
          disabled={!canQueue}
          onClick={onQueue}
          aria-label="Queue for next turn"
          title="Queue as the next turn"
        >
          ↳
        </button>
        <button
          type="button"
          className="send-stop send ready steer"
          onClick={onSteer}
          aria-label="Steer current turn"
          title="Add to the running turn now"
        >
          ↑
        </button>
        <button
          type="button"
          className="send-stop stop"
          onClick={onStop}
          aria-label="Stop"
          title="Stop"
        >
          ■
        </button>
      </div>
    );
  }
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
