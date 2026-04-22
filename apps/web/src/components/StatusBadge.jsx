import { STATUS } from '../config';

// Connection-status pill shown in the header. Maps the session
// status enum to a (dot color, label) pair. Colors pull from the
// CSS palette so a palette tweak propagates here for free.
export function StatusBadge({ status }) {
  const { dotClass, text } = labelFor(status);
  return (
    <span className="status">
      <span className={`dot ${dotClass}`} />
      <span>{text}</span>
    </span>
  );
}

function labelFor(status) {
  switch (status) {
    case STATUS.Connected:
      return { dotClass: 'ok', text: 'connected' };
    case STATUS.Connecting:
    case STATUS.Opening:
      return { dotClass: '', text: 'connecting…' };
    case STATUS.Disconnected:
      return { dotClass: 'warn', text: 'disconnected' };
    case STATUS.Error:
      return { dotClass: 'warn', text: 'error' };
    default:
      return { dotClass: '', text: 'idle' };
  }
}
