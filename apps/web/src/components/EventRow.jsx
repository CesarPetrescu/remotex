import { useState } from 'react';
import { createPortal } from 'react-dom';
import { MarkdownText } from '../util/markdown';
import { CopyButton } from './CopyButton';
import { DiffView } from './DiffView';

// One row in the streaming event list, presented the way Claude Code /
// Codex present their transcripts:
//   - user messages: right-aligned bubble, with attached-image thumbnails
//   - reasoning: "✳ Thinking" — live while streaming, collapsed to one
//     dim headline once done
//   - tool calls: "● tool(arg)" header + a ⎿-gutter output block that
//     tail-follows while running and expands on demand
//   - edits: a real per-file diff (DiffView) instead of a raw diff dump
export function EventRow({ event, pending, grouped }) {
  const isStreaming = pending && !event.completed;

  if (event.role === 'user') {
    return <UserRow event={event} />;
  }

  // Contract (C): the relay's replay buffer had already evicted part of
  // what this client asked for. Say so — a hole in the transcript must
  // never be presented as the whole transcript.
  if (event.role === 'gap') {
    const span = event.missedFrom && event.missedTo
      ? ` (events ${event.missedFrom}–${event.missedTo})`
      : '';
    return (
      <div className="stream-gap" role="note">
        <span className="stream-gap-line" aria-hidden="true" />
        <span className="stream-gap-text">
          earlier events unavailable{span} — the relay no longer has them
        </span>
        <span className="stream-gap-line" aria-hidden="true" />
      </div>
    );
  }

  if (event.role === 'reasoning') {
    return <ThinkingSub event={event} streaming={isStreaming} grouped={grouped} />;
  }

  if (event.role === 'tool') {
    return <ToolSub event={event} streaming={isStreaming} grouped={grouped} />;
  }

  if (event.role === 'agent') {
    return (
      <div className={`sub sub-agent${grouped ? '' : ' standalone'}`}>
        {!grouped && <div className="sub-label">CODEX</div>}
        <MarkdownText text={event.text || ''} trailingCursor={isStreaming} />
      </div>
    );
  }

  return (
    <div className={`sub sub-system${grouped ? '' : ' standalone'}`}>
      <div className="sub-label">{(event.label || 'ITEM').toUpperCase()}</div>
      <div className="item-body">{event.detail || event.label}</div>
    </div>
  );
}

function UserRow({ event }) {
  const [lightbox, setLightbox] = useState(null);
  const body = event.text || (
    event.imageCount > 0 && !event.imageUrls?.length
      ? `${event.imageCount} image${event.imageCount === 1 ? '' : 's'}`
      : ''
  );
  return (
    <div className="item item-user">
      <div className="user-bubble">
        <div className="item-label">YOU</div>
        {body && <div className="item-body">{body}</div>}
        {event.imageUrls?.length > 0 && (
          <div className="item-images">
            {event.imageUrls.map((url, i) => (
              <button
                key={i}
                type="button"
                className="item-image-thumb"
                onClick={() => setLightbox(url)}
                aria-label="View image"
              >
                <img src={url} alt="" />
              </button>
            ))}
          </div>
        )}
      </div>
      {lightbox && createPortal(
        <div
          className="lightbox"
          role="dialog"
          aria-label="Image"
          onClick={() => setLightbox(null)}
        >
          <img src={lightbox} alt="" />
        </div>,
        document.body,
      )}
    </div>
  );
}

// Claude-Code-style thinking: streaming shows the live text; once the
// item completes it folds to a single dim "✳ Thought" line.
function ThinkingSub({ event, streaming, grouped }) {
  const [open, setOpen] = useState(false);
  const expanded = streaming || open;
  const headline = firstLine(event.text) || 'Thinking…';
  return (
    <div className={`sub sub-reasoning${grouped ? '' : ' standalone'}`}>
      <button
        type="button"
        className={`thinking-head ${streaming ? 'streaming' : ''}`}
        onClick={() => !streaming && setOpen((v) => !v)}
        aria-expanded={expanded}
      >
        <span className="thinking-star" aria-hidden="true">✳</span>
        {streaming ? 'Thinking…' : (
          <span className="thinking-headline">
            Thought
            {!open && headline ? <span className="thinking-preview"> · {headline}</span> : null}
          </span>
        )}
      </button>
      {expanded && (
        <MarkdownText text={event.text || '…'} className="dim thinking-body" />
      )}
    </div>
  );
}

function ToolSub({ event, streaming, grouped }) {
  const [expanded, setExpanded] = useState(false);
  const isEdit = event.tool === 'edit';
  const output = event.output || '';
  const failed = Boolean(event.error)
    || (Number.isFinite(event.exitCode) && event.exitCode !== 0);
  const dotClass = streaming ? 'run' : failed ? 'err' : 'ok';
  const arg = firstLine(event.command);
  const hasRawDetails = event.rawArguments !== undefined
    || event.rawResult !== undefined
    || event.error;

  return (
    <div className={`sub sub-tool${grouped ? '' : ' standalone'}`}>
      <button
        type="button"
        className="tool-head"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <span className={`tool-dot ${dotClass}`} aria-hidden="true" />
        <span className="tool-name">{event.tool}</span>
        {arg && !isEdit && <span className="tool-arg">({arg})</span>}
        <span className="tool-meta">{toolMeta(event)}</span>
      </button>

      {isEdit ? (
        <div className="tool-out">
          <DiffView summary={event.command} diff={output} streaming={streaming} />
        </div>
      ) : output ? (
        <ToolOutput
          output={output}
          streaming={streaming}
          expanded={expanded}
          onExpand={() => setExpanded(true)}
        />
      ) : null}

      {expanded && hasRawDetails && (
        <div className="tool-details">
          {event.rawArguments !== undefined && (
            <ToolDetail label="arguments" value={event.rawArguments} />
          )}
          {event.rawResult !== undefined && (
            <ToolDetail label="result" value={event.rawResult} />
          )}
          {event.error && <ToolDetail label="error" value={event.error} />}
        </div>
      )}
    </div>
  );
}

// The ⎿ gutter block. While the tool is running it tail-follows (last
// lines visible, like watching a terminal); once done it shows the head
// with an expand affordance.
function ToolOutput({ output, streaming, expanded, onExpand }) {
  const lines = output.split('\n');
  const limit = streaming ? 4 : 5;
  const overflow = lines.length > limit;
  const shown = expanded || !overflow
    ? output
    : streaming
      ? lines.slice(-limit).join('\n')
      : `${lines.slice(0, limit - 1).join('\n')}\n`;

  return (
    <div className="tool-out">
      <span className="tool-gutter" aria-hidden="true">⎿</span>
      <pre className="tool-out-body">
        <CopyButton getText={() => output} />
        {overflow && streaming && !expanded && <span className="tool-out-ellipsis">…{'\n'}</span>}
        {shown}
        {overflow && !streaming && !expanded && (
          <span
            className="tool-expand"
            role="button"
            tabIndex={0}
            onClick={(ev) => {
              ev.stopPropagation();
              onExpand();
            }}
            onKeyDown={(ev) => {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                onExpand();
              }
            }}
          >
            … {lines.length - limit + 1} more lines
          </span>
        )}
      </pre>
    </div>
  );
}

function ToolDetail({ label, value }) {
  const text = typeof value === 'string' ? value : stringify(value);
  return (
    <div className="tool-detail">
      <div className="tool-detail-label">{label}</div>
      <pre className="item-code dim">
        <CopyButton getText={() => text} />
        {text}
      </pre>
    </div>
  );
}

function toolMeta(event) {
  const parts = [];
  if (Number.isFinite(event.exitCode) && event.exitCode !== 0) parts.push(`exit ${event.exitCode}`);
  if (event.status && event.status !== 'completed') parts.push(event.status);
  if (Number.isFinite(event.durationMs)) parts.push(formatMs(event.durationMs));
  if (event.error) parts.push('error');
  return parts.join(' · ');
}

function formatMs(ms) {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)}s`;
}

function firstLine(text) {
  // Strip markdown emphasis — headlines render as plain text.
  const line = (text || '').split('\n')[0].trim().replace(/\*\*?|__/g, '');
  return line.length > 80 ? `${line.slice(0, 77)}…` : line;
}

function stringify(value) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
