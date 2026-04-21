export default function Header({ status }) {
  const { kind, text } = statusDisplay(status);
  return (
    <header className="bar">
      <span className="brand">REMOTEX</span>
      <span className="status">
        <span className={`dot ${kind}`} />
        <span>{text}</span>
      </span>
    </header>
  );
}

function statusDisplay(status) {
  switch (status) {
    case 'connected':
      return { kind: 'ok', text: 'connected' };
    case 'connecting':
    case 'opening':
      return { kind: '', text: 'connecting…' };
    case 'closed':
    case 'disconnected':
      return { kind: 'warn', text: 'disconnected' };
    case 'error':
      return { kind: 'warn', text: 'error' };
    default:
      return { kind: '', text: 'idle' };
  }
}
