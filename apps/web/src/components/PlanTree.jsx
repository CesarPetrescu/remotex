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
  const steps = orchestrator?.steps || [];
  const finished = orchestrator?.finished;
  const running = steps.filter((s) => s.status === 'running').length;
  const done = steps.filter((s) => s.status === 'completed').length;
  const failed = steps.filter((s) => s.status === 'failed' || s.status === 'cancelled').length;

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
      </div>
      {steps.length === 0 ? (
        <div className="plan-tree-hint">
          waiting for the orchestrator to submit a plan…
        </div>
      ) : (
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
