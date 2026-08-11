import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { EventRow } from './EventRow';
import { relativeAge } from '../util/time';

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
      // Consecutive reasoning items collapse into ONE block — history
      // replay emits each summary part as its own item, which otherwise
      // renders as a stack of identical "REASONING" rows.
      const tail = prev.events[prev.events.length - 1];
      if (e.role === 'reasoning' && tail?.role === 'reasoning') {
        prev.events[prev.events.length - 1] = {
          ...tail,
          text: [tail.text, e.text].filter(Boolean).join('\n\n'),
          completed: e.completed,
        };
        continue;
      }
      prev.events.push(e);
    } else {
      groups.push({ kind: 'agent', events: [e] });
    }
  }
  return groups;
}

// Epoch seconds for a group (first event that has one); tolerates ms.
function groupTs(g) {
  const raw = g.events.find((e) => Number.isFinite(e.ts))?.ts;
  if (!Number.isFinite(raw)) return null;
  return raw > 1e12 ? raw / 1000 : raw;
}

const TIME_GAP_S = 30 * 60;

// How close to the end counts as "at the tail" — the slack that lets a
// half-finished line or a settling image still count as following along.
const TAIL_SLACK_PX = 140;

export const STREAM_A11Y_PROPS = {
  role: 'log',
  'aria-live': 'polite',
  // Announce newly appended rows without re-reading every streaming delta.
  'aria-relevant': 'additions',
};

// Exported for tests: jsdom has no layout, so the predicate is kept pure and
// the component feeds it real geometry.
export function isNearTail({ scrollHeight, scrollTop, clientHeight }) {
  return scrollHeight - scrollTop - clientHeight < TAIL_SLACK_PX;
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
// Claude-Code-style activity line: pulsing star + elapsed seconds while
// a turn runs, rendered at the tail of the stream.
function WorkingRow({ sinceMs, onStop }) {
  const [, setTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const elapsed = sinceMs > 0 ? Math.max(0, Math.floor((Date.now() - sinceMs) / 1000)) : 0;
  return (
    <div className="working-row" aria-live="polite">
      <span className="working-star" aria-hidden="true">✳</span>
      Working… {elapsed > 0 ? `${elapsed}s` : ''}
      {onStop && (
        // Stopping acts on the turn, so the control lives with the turn —
        // not next to send, where it was one mis-tap from your draft.
        <button type="button" className="working-stop" onClick={onStop} title="Stop this turn">
          <span aria-hidden="true">■</span> stop
        </button>
      )}
    </div>
  );
}

export function EventStream({
  events,
  pending,
  pendingSinceMs = 0,
  placeholder,
  historyHasMore = false,
  historyLoading = false,
  historyTick = 0,
  historyPrepend = false,
  onLoadOlder,
  onAtBottomChange,
  onStop,
}) {
  const scrollerRef = useRef(null);
  const sentinelRef = useRef(null);
  const committedTick = useRef(0);
  const preCommit = useRef(null);
  const [armed, setArmed] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const atBottomRef = useRef(true);

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

  // Single source of truth for "is the user at the tail". Kept in a ref as
  // well as state because the follow effect below needs the value from
  // *before* the new content landed, and state is a render behind.
  const measure = () => {
    const el = scrollerRef.current;
    if (!el) return;
    const near = isNearTail(el);
    if (near === atBottomRef.current) return;
    atBottomRef.current = near;
    setAtBottom(near);
    onAtBottomChange?.(near);
  };

  // Live streaming: follow the tail if the user was already there.
  //
  // The decision uses the pre-growth ref, not the geometry we can see now:
  // one big delta can move the bottom more than 140px in a single commit, and
  // re-measuring here would read that as "the user scrolled up" and stop
  // following — leaving the transcript stuck mid-stream. Growing content fires
  // no scroll event either (overflow-anchor is off), so measuring afterwards
  // is also what keeps `atBottom` honest.
  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    if (atBottomRef.current) el.scrollTop = el.scrollHeight;
    measure();
    // `measure` is recreated every render; listing it here would re-pin the
    // scroller on every render, not just when new events land.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events]);

  const onScroll = measure;

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
    <div
      className="stream"
      ref={scrollerRef}
      onScroll={onScroll}
      {...STREAM_A11Y_PROPS}
    >
      {!events.length && (
        <div className="empty">{placeholder || 'send a prompt to start…'}</div>
      )}
      {events.length > 0 && historyHasMore && (
        <div ref={sentinelRef} className="history-sentinel" aria-live="polite">
          {historyLoading ? 'loading older turns…' : 'older turns load as you scroll'}
        </div>
      )}
      {groups.map((g, gi) => {
        const ts = groupTs(g);
        const prevTs = gi > 0 ? groupTs(groups[gi - 1]) : null;
        const divider = ts != null && prevTs != null && ts - prevTs > TIME_GAP_S ? (
          <div className="time-divider" key={`t-${gi}`} aria-hidden="true">
            {relativeAge(Math.floor(ts))}
          </div>
        ) : null;
        const row = (g.kind === 'user' || g.kind === 'gap') ? (
          <EventRow key={g.events[0].id} event={g.events[0]} pending={pending} grouped={false} />
        ) : (
          <div className="agent-group" key={`g-${gi}-${g.events[0].id}`}>
            <div className="agent-group-label">CODEX</div>
            <div className="agent-group-body">
              {g.events.map((e) => (
                <EventRow key={e.id} event={e} pending={pending} grouped />
              ))}
            </div>
          </div>
        );
        return divider ? [divider, row] : row;
      })}
      {pending && events.length > 0 && (
        <WorkingRow sinceMs={pendingSinceMs} onStop={onStop} />
      )}
      {events.length > 0 && !atBottom && (
        <button
          type="button"
          className="jump-to-bottom"
          onClick={() => {
            const el = scrollerRef.current;
            if (!el) return;
            el.scrollTop = el.scrollHeight;
            atBottomRef.current = true;
            measure();
          }}
          aria-label="Jump to latest"
        >
          ↓
        </button>
      )}
    </div>
  );
}
