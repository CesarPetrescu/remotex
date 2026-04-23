import { useEffect, useRef } from 'react';
import { EventRow } from './EventRow';

// Groups consecutive non-user events into a single "AGENT" block so a
// chain of reasoning / agent / tool steps reads as one turn under one
// header, with a continuous left accent stripe rendered by CSS.
function groupEvents(events) {
  const groups = [];
  for (const e of events) {
    if (e.role === 'user') {
      groups.push({ kind: 'user', events: [e] });
      continue;
    }
    const prev = groups[groups.length - 1];
    if (prev && prev.kind === 'agent') {
      prev.events.push(e);
    } else {
      groups.push({ kind: 'agent', events: [e] });
    }
  }
  return groups;
}

export function EventStream({ events, pending, placeholder }) {
  const tailRef = useRef(null);

  useEffect(() => {
    tailRef.current?.scrollIntoView({ block: 'end' });
  }, [events]);

  if (!events.length) {
    return <div className="empty">{placeholder || 'send a prompt to start…'}</div>;
  }

  const groups = groupEvents(events);

  return (
    <div className="stream">
      {groups.map((g, gi) => {
        if (g.kind === 'user') {
          const e = g.events[0];
          return <EventRow key={e.id} event={e} pending={pending} grouped={false} />;
        }
        return (
          <div className="agent-group" key={`g-${gi}-${g.events[0].id}`}>
            <div className="agent-group-label">AGENT</div>
            <div className="agent-group-body">
              {g.events.map((e) => (
                <EventRow key={e.id} event={e} pending={pending} grouped />
              ))}
            </div>
          </div>
        );
      })}
      <div ref={tailRef} />
    </div>
  );
}
