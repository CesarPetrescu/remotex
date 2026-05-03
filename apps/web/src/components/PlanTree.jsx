// Renders the orchestrator's DAG of steps with live status. Steps are
// shown in plan order; deps are surfaced as a tiny "← s1, s2" chip on
// each row so the user can see why a step is waiting.

const STATUS_LABEL = {
  pending: 'queued',
  running: 'running',
  completed: 'done',
  failed: 'failed',
  cancelled: 'cancelled',
};

export function PlanTree({ orchestrator }) {
  const steps = orchestrator?.steps || [];
  if (!steps.length) {
    return (
      <div className="plan-tree empty">
        <div className="plan-tree-label">PLAN</div>
        <div className="plan-tree-hint">
          waiting for the orchestrator to submit a plan…
        </div>
      </div>
    );
  }
  return (
    <div className="plan-tree">
      <div className="plan-tree-label">PLAN</div>
      <ol className="plan-tree-steps">
        {steps.map((s) => (
          <li key={s.step_id} className={`plan-step plan-step-${s.status || 'pending'}`}>
            <div className="plan-step-row">
              <span className="plan-step-id">{s.step_id}</span>
              <span className="plan-step-title">{s.title || s.step_id}</span>
              <span className="plan-step-status">
                {STATUS_LABEL[s.status] || s.status || '—'}
              </span>
            </div>
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
            {s.summary && (
              <div className="plan-step-summary" title={s.summary}>
                {s.summary.length > 240 ? s.summary.slice(0, 240) + '…' : s.summary}
              </div>
            )}
          </li>
        ))}
      </ol>
      {orchestrator?.finished && (
        <div className={`plan-tree-finished ${orchestrator.finished.ok ? 'ok' : 'err'}`}>
          {orchestrator.finished.ok
            ? `✓ ${orchestrator.finished.summary || 'orchestration complete'}`
            : `✗ ${orchestrator.finished.error || 'orchestrator stopped'}`}
        </div>
      )}
    </div>
  );
}
