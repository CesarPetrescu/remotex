import { useState } from 'react';
import { EventRow } from './EventRow';

// Plan body. Lives inside the right sidebar's PLAN tab (the sidebar
// owns the chrome + close button). Per-step rows show status pill,
// deps chip, summary, and a live block that streams the child's
// agent text + current tool label while the step is running.

const STATUS_LABEL = {
  pending: 'queued',
  running: 'running',
  completed: 'done',
  failed: 'failed',
  cancelled: 'cancelled',
};

export function PlanTree({ orchestrator }) {
  const [selectedAgentId, setSelectedAgentId] = useState(null);
  const steps = orchestrator?.steps || [];
  const brainSubagents = orchestrator?.brainSubagents || [];
  const agents = orchestrator?.agents || {};
  const selectedAgent = selectedAgentId ? agents[selectedAgentId] : null;
  const finished = orchestrator?.finished;
  const running = steps.filter((s) => s.status === 'running').length;
  const done = steps.filter((s) => s.status === 'completed').length;
  const failed = steps.filter((s) => s.status === 'failed' || s.status === 'cancelled').length;
  const hasRows = steps.length > 0 || brainSubagents.length > 0;

  if (selectedAgent) {
    return (
      <AgentTranscript
        agent={selectedAgent}
        agents={agents}
        onBack={() => setSelectedAgentId(null)}
        onSelectAgent={setSelectedAgentId}
      />
    );
  }

  return (
    <div className="plan-tree">
      <div className="plan-tree-summary-row">
        {steps.length > 0 && (
          <>
            <span>{steps.length} {steps.length === 1 ? 'step' : 'steps'}</span>
            {running > 0 && <span className="plan-tag plan-tag-running">{running} running</span>}
            {done > 0 && <span className="plan-tag plan-tag-done">{done} done</span>}
            {failed > 0 && <span className="plan-tag plan-tag-failed">{failed} failed</span>}
          </>
        )}
        {steps.length === 0 && brainSubagents.length > 0 && (
          <span>{brainSubagents.length} native agent {brainSubagents.length === 1 ? 'call' : 'calls'}</span>
        )}
      </div>
      {!hasRows ? (
        <div className="plan-tree-hint">
          waiting for the orchestrator to submit a plan…
        </div>
      ) : (
        <ol className="plan-tree-steps">
          {brainSubagents.length > 0 && (
            <li className="plan-step plan-step-brain">
              <button
                type="button"
                className="plan-step-row plan-step-button"
                onClick={() => setSelectedAgentId('brain')}
              >
                <span className="plan-step-id">brain</span>
                <span className="plan-step-title">native subagents</span>
                <span className="plan-step-status">{brainSubagents.length}</span>
              </button>
              <SubagentTrace events={brainSubagents} onSelectAgent={setSelectedAgentId} />
            </li>
          )}
          {steps.map((s) => (
            <li key={s.step_id} className={`plan-step plan-step-${s.status || 'pending'}`}>
              <button
                type="button"
                className="plan-step-row plan-step-button"
                onClick={() => setSelectedAgentId(`step:${s.step_id}`)}
              >
                <span className="plan-step-id">{s.step_id}</span>
                <span className="plan-step-title">{s.title || s.step_id}</span>
                <span className="plan-step-status">
                  {STATUS_LABEL[s.status] || s.status || '—'}
                </span>
              </button>
              {(s.deps || []).length > 0 && (
                <div className="plan-step-deps">← {s.deps.join(', ')}</div>
              )}
              {s.status === 'running' && s.live && (s.live.label || s.live.text) && (
                <div className="plan-step-live">
                  {s.live.label && (
                    <div className="plan-step-live-label">
                      <span className="plan-step-live-spinner" /> {s.live.label}
                    </div>
                  )}
                  {s.live.text && (
                    <pre className="plan-step-live-text">{s.live.text}</pre>
                  )}
                </div>
              )}
              {s.live?.subagents?.length > 0 && (
                <SubagentTrace events={s.live.subagents} onSelectAgent={setSelectedAgentId} />
              )}
              {s.summary && (
                <div className="plan-step-summary" title={s.summary}>
                  {s.summary.length > 240 ? s.summary.slice(0, 240) + '…' : s.summary}
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
      {finished && (
        <div className={`plan-tree-finished ${finished.ok ? 'ok' : 'err'}`}>
          {finished.ok
            ? `✓ ${finished.summary || 'orchestration complete'}`
            : `✗ ${finished.error || 'orchestrator stopped'}`}
        </div>
      )}
    </div>
  );
}

function AgentTranscript({ agent, agents, onBack, onSelectAgent }) {
  const children = Object.values(agents || {}).filter((a) => a.parent_agent_id === agent.id);
  return (
    <div className="agent-transcript">
      <div className="agent-transcript-head">
        <button type="button" className="agent-transcript-back" onClick={onBack}>‹ plan</button>
        <div className="agent-transcript-title">
          <span>{agent.label || agent.id}</span>
          <small>{agent.status || 'running'}{agent.thread_id ? ` · ${shortThread(agent.thread_id)}` : ''}</small>
        </div>
      </div>
      {children.length > 0 && (
        <div className="agent-transcript-children">
          {children.map((child) => (
            <button
              key={child.id}
              type="button"
              className="agent-child-chip"
              onClick={() => onSelectAgent(child.id)}
            >
              {child.label || shortThread(child.id)}
            </button>
          ))}
        </div>
      )}
      <div className="agent-transcript-events">
        {agent.events?.length > 0 ? (
          agent.events.map((event) => (
            <EventRow key={event.id} event={event} pending={agent.status === 'running'} grouped />
          ))
        ) : (
          <div className="plan-tree-hint">no transcript events yet</div>
        )}
      </div>
    </div>
  );
}

function SubagentTrace({ events, onSelectAgent }) {
  if (!events?.length) return null;
  return (
    <div className="plan-subagents" aria-label="native subagent activity">
      {events.map((event) => (
        <button
          type="button"
          key={event.id}
          className={`plan-subagent-row plan-subagent-${statusClass(event.status)}`}
          onClick={() => event.agent_id && onSelectAgent?.(event.agent_id)}
          disabled={!event.agent_id}
          style={{ '--depth': Math.max((event.depth || 1) - 1, 0) }}
        >
          <span className="plan-subagent-rail" />
          <span className="plan-subagent-status">{statusLabel(event.status)}</span>
          <span className="plan-subagent-main">
            <span className="plan-subagent-title">{event.label || 'subagent'}</span>
            {event.prompt && (
              <span className="plan-subagent-prompt">{truncate(event.prompt, 96)}</span>
            )}
            {event.agents_states?.length > 0 && (
              <span className="plan-subagent-states">
                {event.agents_states.map((agent) => (
                  <span
                    key={agent.thread_id}
                    className={`plan-subagent-state plan-subagent-${statusClass(agent.status)}`}
                  >
                    {shortThread(agent.thread_id)} {agent.status}
                    {agent.message ? `: ${truncate(agent.message, 60)}` : ''}
                  </span>
                ))}
              </span>
            )}
          </span>
          <span className="plan-subagent-meta">
            {event.model || shortThread(event.receiver_thread_ids?.[0])}
          </span>
        </button>
      ))}
    </div>
  );
}

function statusLabel(status) {
  switch (status) {
    case 'completed':
      return 'done';
    case 'failed':
    case 'errored':
      return 'fail';
    case 'running':
    case 'inProgress':
    case 'pendingInit':
      return 'run';
    case 'shutdown':
      return 'closed';
    default:
      return status || 'run';
  }
}

function statusClass(status) {
  switch (status) {
    case 'completed':
      return 'done';
    case 'failed':
    case 'errored':
      return 'failed';
    case 'shutdown':
      return 'closed';
    default:
      return 'running';
  }
}

function shortThread(threadId) {
  if (!threadId) return '';
  return threadId.length > 8 ? threadId.slice(0, 8) : threadId;
}

function truncate(value, max) {
  if (!value || value.length <= max) return value || '';
  return `${value.slice(0, max)}…`;
}
