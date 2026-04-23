import { MarkdownText } from '../util/markdown';
import { CopyButton } from './CopyButton';

// One row in the streaming event list. Picks a renderer per role so
// reasoning, tool calls, and the agent reply each have their own shape
// (left-accent color, body style) while user messages stay plain.
export function EventRow({ event, pending }) {
  const isStreaming = pending && !event.completed;
  if (event.role === 'reasoning') {
    return (
      <details className="item item-reasoning" open={!event.replayed}>
        <summary className="item-label">{labelFor(event)}</summary>
        <MarkdownText text={event.text || '…'} className="dim" />
      </details>
    );
  }
  return (
    <div className={`item item-${event.role}`}>
      <div className="item-label">{labelFor(event)}</div>
      {renderBody(event, isStreaming)}
    </div>
  );
}

function labelFor(event) {
  switch (event.role) {
    case 'user':
      return 'USER';
    case 'reasoning':
      return 'REASONING';
    case 'tool':
      return `TOOL · ${event.tool}`;
    case 'agent':
      return 'AGENT';
    default:
      return (event.label || 'ITEM').toUpperCase();
  }
}

function renderBody(event, streaming) {
  switch (event.role) {
    case 'user':
      return (
        <>
          <div className="item-body">{event.text}</div>
          {event.imageUrls?.length > 0 && (
            <div className="item-images">
              {event.imageUrls.map((url, i) => (
                <img key={i} src={url} alt="" />
              ))}
            </div>
          )}
        </>
      );
    case 'reasoning':
      return <MarkdownText text={event.text || '…'} className="dim" />;
    case 'tool':
      return (
        <>
          {event.command && (
            <pre className="item-code">
              <CopyButton getText={() => event.command} />
              {event.command}
            </pre>
          )}
          {event.output && (
            <pre className="item-code dim">
              <CopyButton getText={() => event.output} />
              {event.output}
            </pre>
          )}
        </>
      );
    case 'agent':
      return <MarkdownText text={event.text || ''} trailingCursor={streaming} />;
    default:
      return <div className="item-body">{event.detail || event.label}</div>;
  }
}
