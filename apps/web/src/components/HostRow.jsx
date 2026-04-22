export function HostRow({ host, onClick }) {
  return (
    <button
      type="button"
      className={`host ${host.online ? 'online' : ''}`}
      onClick={() => onClick(host)}
      disabled={!host.online}
    >
      <span className={`dot ${host.online ? 'ok' : ''}`} />
      <div className="host-body">
        <div className="nick">{host.nickname}</div>
        <div className="sub">
          {(host.hostname || '—') + ' · ' + (host.online ? 'online' : 'offline')}
        </div>
        {host.online && <div className="host-hint">tap to open session →</div>}
      </div>
    </button>
  );
}
