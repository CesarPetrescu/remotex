import { useState } from 'react';
import { MarkdownText } from '../util/markdown';
import { CopyButton } from './CopyButton';

// One row in the streaming event list. Inside an agent-group (grouped=true)
// the top-level "AGENT" label is rendered by the parent; rows render only
// a small sub-label ("REASONING", "TOOL · …") plus body.
export function EventRow({ event, pending, grouped }) {
  const isStreaming = pending && !event.completed;

  if (event.role === 'user') {
    const body = event.text || (
      event.imageCount > 0
        ? `${event.imageCount} image${event.imageCount === 1 ? '' : 's'}`
        : ''
    );
    return (
      <div className="item item-user">
        <div className="user-bubble">
          <div className="item-label">USER</div>
          <div className="item-body">{body}</div>
          {event.imageUrls?.length > 0 && (
            <div className="item-images">
              {event.imageUrls.map((url, i) => (
                <img key={i} src={url} alt="" />
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  if (event.role === 'reasoning') {
    return (
      <details className={`sub sub-reasoning${grouped ? '' : ' standalone'}`} open={!event.replayed}>
        <summary className="sub-label">REASONING</summary>
        <MarkdownText text={event.text || '…'} className="dim" />
      </details>
    );
  }

  if (event.role === 'tool') {
    return <ToolSub event={event} grouped={grouped} />;
  }

  if (event.role === 'agent') {
    return (
      <div className={`sub sub-agent${grouped ? '' : ' standalone'}`}>
        {!grouped && <div className="sub-label">AGENT</div>}
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

// Default-collapsed: shows command and a 2/…/2 preview of output. Click to
// expand — toggles the whole block (command stays visible either way, but
// output becomes full).
function ToolSub({ event, grouped }) {
  const [expanded, setExpanded] = useState(false);
  const output = event.output || '';
  const needsTruncation = output && countLines(output) > 5;
  const shown = expanded || !needsTruncation ? output : previewLines(output);
  const hasRawDetails = event.rawArguments !== undefined || event.rawResult !== undefined || event.error;
  const meta = toolMeta(event);

  return (
    <div className={`sub sub-tool${grouped ? '' : ' standalone'}`}>
      <button
        type="button"
        className="sub-label sub-label-toggle"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <span className="sub-chev">{expanded ? '▾' : '▸'}</span>
        TOOL · {event.tool}
        {meta && <span className="tool-meta"> · {meta}</span>}
      </button>
      {event.command && (
        <pre className="item-code">
          <CopyButton getText={() => event.command} />
          {event.command}
        </pre>
      )}
      {output && (
        <pre className="item-code dim">
          <CopyButton getText={() => output} />
          {shown}
          {needsTruncation && !expanded && (
            <span
              className="tool-expand"
              role="button"
              tabIndex={0}
              onClick={(ev) => {
                ev.stopPropagation();
                setExpanded(true);
              }}
              onKeyDown={(ev) => {
                if (ev.key === 'Enter' || ev.key === ' ') {
                  ev.preventDefault();
                  setExpanded(true);
                }
              }}
            >
              {`\n… ${countLines(output) - 4} more lines — click to expand`}
            </span>
          )}
        </pre>
      )}
      {expanded && hasRawDetails && (
        <div className="tool-details">
          {event.rawArguments !== undefined && (
            <ToolDetail label="arguments" value={event.rawArguments} />
          )}
          {event.rawResult !== undefined && (
            <ToolDetail label="result" value={event.rawResult} />
          )}
          {event.error && (
            <ToolDetail label="error" value={event.error} />
          )}
        </div>
      )}
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
  if (event.status) parts.push(event.status);
  if (Number.isFinite(event.durationMs)) parts.push(`${event.durationMs}ms`);
  if (event.error) parts.push('error');
  return parts.join(' · ');
}

function stringify(value) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function countLines(s) {
  if (!s) return 0;
  return s.split('\n').length;
}

function previewLines(s) {
  const lines = s.split('\n');
  if (lines.length <= 5) return s;
  const head = lines.slice(0, 2);
  const tail = lines.slice(-2);
  return [...head, '…', ...tail].join('\n');
}
