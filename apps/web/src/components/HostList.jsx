export default function HostList({ hosts, selected, onSelect, loading }) {
  if (loading) return <div className="empty">loading…</div>;
  if (!hosts.length) return <div className="empty">no hosts yet</div>;

  return (
    <div className="hosts">
      {hosts.map((h) => (
        <div
          key={h.id}
          className={`host${selected === h.id ? ' selected' : ''}`}
          onClick={() => onSelect(h)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              onSelect(h);
            }
          }}
        >
          <div>
            <span className={`dot${h.online ? ' ok' : ''}`} />
            <span className="nick">{h.nickname}</span>
          </div>
          <div className="sub">
            {h.hostname || '—'} · {h.online ? 'online' : 'offline'}
          </div>
        </div>
      ))}
    </div>
  );
}
