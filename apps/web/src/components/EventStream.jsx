import { useEffect, useRef } from 'react';
import { EventRow } from './EventRow';

export function EventStream({ events, pending, placeholder }) {
  const tailRef = useRef(null);

  useEffect(() => {
    tailRef.current?.scrollIntoView({ block: 'end' });
  }, [events]);

  if (!events.length) {
    return <div className="empty">{placeholder || 'send a prompt to start…'}</div>;
  }
  return (
    <div className="stream">
      {events.map((e) => (
        <EventRow key={e.id} event={e} pending={pending} />
      ))}
      <div ref={tailRef} />
    </div>
  );
}
