import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { EventRow } from './EventRow';

// Groups consecutive non-user events into a single "CODEX" block so a
// chain of reasoning / agent / tool steps reads as one turn under one
// header, with a continuous left accent stripe rendered by CSS.
function groupEvents(events) {
  const groups = [];
  for (const e of events) {
    if (e.role === 'user') {
      groups.push({ kind: 'user', events: [e] });
      continue;
    }
    // A replay gap is a marker about the transcript itself, not a codex
    // step — it stands alone rather than joining a CODEX block.
    if (e.role === 'gap') {
      groups.push({ kind: 'gap', events: [e] });
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

// Scroll contract:
//  - a committed history TAIL pins the view to the bottom in one jump
//    (no streaming past the user);
//  - a committed history CHUNK (scroll-up backfill) keeps the exact
//    turns you were looking at in place, adjusting scrollTop by the
//    prepended height;
//  - live events auto-follow only while you're already near the bottom,
//    so reading old turns never gets yanked down by a streaming delta;
//  - the "load older" sentinel arms 300 ms after the tail settles, so
//    the initial render can't accidentally trigger a backfill.
export function EventStream({
  events,
  pending,
  placeholder,
  historyHasMore = false,
  historyLoading = false,
  historyTick = 0,
  historyPrepend = false,
  onLoadOlder,
}) {
  const scrollerRef = useRef(null);
  const sentinelRef = useRef(null);
  const committedTick = useRef(0);
  const preCommit = useRef(null);
  const [armed, setArmed] = useState(false);

  // Render-phase snapshot: when this render is committing a prepend batch,
  // capture the scroll geometry BEFORE React mutates the DOM. (Function
  // components have no getSnapshotBeforeUpdate; reading here is the
  // equivalent — a re-invoked render just re-reads the same values.)
  if (
    historyPrepend &&
    historyTick !== committedTick.current &&
    scrollerRef.current
  ) {
    preCommit.current = {
      height: scrollerRef.current.scrollHeight,
      top: scrollerRef.current.scrollTop,
    };
  }

  useLayoutEffect(() => {
    if (historyTick === committedTick.current) return undefined;
    committedTick.current = historyTick;
    const el = scrollerRef.current;
    if (!el) return undefined;
    if (historyPrepend && preCommit.current) {
      el.scrollTop = preCommit.current.top + (el.scrollHeight - preCommit.current.height);
      preCommit.current = null;
      return undefined;
    }
    // Initial tail: one jump to the newest exchange, then arm backfill.
    el.scrollTop = el.scrollHeight;
    const t = setTimeout(() => setArmed(true), 300);
    return () => clearTimeout(t);
  }, [historyTick, historyPrepend]);

  // Live streaming: follow the tail only while the user is at the tail.
  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 140;
    if (nearBottom) el.scrollTop = el.scrollHeight;
  }, [events]);

  // New session → disarm until its tail lands.
  useEffect(() => {
    if (!events.length) setArmed(false);
  }, [events.length]);

  useEffect(() => {
    if (!armed || !historyHasMore || !onLoadOlder) return undefined;
    const el = sentinelRef.current;
    const root = scrollerRef.current;
    if (!el || !root) return undefined;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) onLoadOlder();
      },
      { root, rootMargin: '200px 0px 0px 0px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [armed, historyHasMore, onLoadOlder]);

  const groups = groupEvents(events);

  return (
    <div className="stream" ref={scrollerRef}>
      {!events.length && (
        <div className="empty">{placeholder || 'send a prompt to start…'}</div>
      )}
      {events.length > 0 && historyHasMore && (
        <div ref={sentinelRef} className="history-sentinel" aria-live="polite">
          {historyLoading ? 'loading older turns…' : 'older turns load as you scroll'}
        </div>
      )}
      {groups.map((g, gi) => {
        if (g.kind === 'user' || g.kind === 'gap') {
          const e = g.events[0];
          return <EventRow key={e.id} event={e} pending={pending} grouped={false} />;
        }
        return (
          <div className="agent-group" key={`g-${gi}-${g.events[0].id}`}>
            <div className="agent-group-label">CODEX</div>
            <div className="agent-group-body">
              {g.events.map((e) => (
                <EventRow key={e.id} event={e} pending={pending} grouped />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
