import { useEffect, useRef } from 'react';

export default function EventStream({ events, pending, placeholder }) {
  const endRef = useRef(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [events]);

  if (!events.length) {
    return <div className="empty">{placeholder || 'Load hosts, pick one, open a session.'}</div>;
  }

  return (
    <>
      {events.map((e) => (
        <EventItem key={e.id} event={e} pending={pending} />
      ))}
      <div ref={endRef} />
    </>
  );
}

function EventItem({ event, pending }) {
  if (event.kind === 'user') {
    return (
      <div className="item user">
        <div className="lbl">user</div>
        <div>{event.text}</div>
      </div>
    );
  }

  if (event.itemType === 'agent_reasoning') {
    return (
      <div className="item reason">
        <div className="lbl">reasoning</div>
        <div>{event.text || '…'}</div>
      </div>
    );
  }

  if (event.itemType === 'tool_call') {
    const cmd = event.args?.command || (event.args ? JSON.stringify(event.args) : '');
    return (
      <div className="item tool">
        <div className="lbl">tool · {event.tool || 'unknown'}</div>
        {cmd && <pre>{cmd}</pre>}
        {event.output && <pre style={{ opacity: 0.8 }}>{event.output}</pre>}
      </div>
    );
  }

  if (event.itemType === 'agent_message') {
    return (
      <div className="item agent">
        <div className="lbl">agent</div>
        <div>
          {event.text}
          {pending && !event.completed && <span className="cursor" />}
        </div>
      </div>
    );
  }

  return (
    <div className="item">
      <div className="lbl">{event.itemType || 'item'}</div>
      <div>{event.text}</div>
    </div>
  );
}
