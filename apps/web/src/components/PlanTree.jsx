import { useEffect, useState } from 'react';

// Renders the orchestrator's DAG of steps with live status. Steps are
// shown in plan order; deps are surfaced as a tiny "← s1, s2" chip on
// each row so the user can see why a step is waiting.
//
// Collapsible: the panel header doubles as a tap-target. State is
// local — collapsing is a viewing preference, not part of the
// session model. Auto-expands when a step starts running so the
// user sees live progress without manually re-opening the panel.

const STATUS_LABEL = {
  pending: 'queued',
  running: 'running',
  completed: 'done',
  failed: 'failed',
  cancelled: 'cancelled',
};

export function PlanTree({ orchestrator }) {
  const steps = orchestrator?.steps || [];
  const runningCount = steps.filter((s) => s.status === 'running').length;
  const doneCount = steps.filter((s) => s.status === 'completed').length;
  const failedCount = steps.filter((s) => s.status === 'failed' || s.status === 'cancelled').length;
  const finished = orchestrator?.finished;

  const [collapsed, setCollapsed] = useState(false);
  // Re-expand whenever a step starts running so the live block isn't
  // hidden behind a collapsed header.
  useEffect(() => {
    if (runningCount > 0) setCollapsed(false);
  }, [runningCount]);

  if (!steps.length && !finished) {
    return (
      <div className="plan-tree empty">
        <button
          type="button"
          className="plan-tree-header"
          onClick={() => setCollapsed((v) => !v)}
          aria-expanded={!collapsed}
        >
          <span className="plan-tree-chevron">{collapsed ? '▸' : '▾'}</span>
          <span className="plan-tree-label">PLAN</span>
          <span className="plan-tree-summary">waiting…</span>
        </button>
        {!collapsed && (
          <div className="plan-tree-hint">
            waiting for the orchestrator to submit a plan…
          </div>
        )}
      </div>
    );
  }
  return (
    <div className={`plan-tree${collapsed ? ' collapsed' : ''}`}>
      <button
        type="button"
        className="plan-tree-header"
        onClick={() => setCollapsed((v) => !v)}
        aria-expanded={!collapsed}
      >
        <span className="plan-tree-chevron">{collapsed ? '▸' : '▾'}</span>
        <span className="plan-tree-label">PLAN</span>
        <span className="plan-tree-summary">
          {steps.length} {steps.length === 1 ? 'step' : 'steps'}
          {runningCount > 0 && ` · ${runningCount} running`}
          {doneCount > 0 && ` · ${doneCount} done`}
          {failedCount > 0 && ` · ${failedCount} failed`}
          {finished && (finished.ok ? ' · ✓ done' : ' · ✗ stopped')}
        </span>
      </button>
      {!collapsed && (
        <>
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
          {finished && (
            <div className={`plan-tree-finished ${finished.ok ? 'ok' : 'err'}`}>
              {finished.ok
                ? `✓ ${finished.summary || 'orchestration complete'}`
                : `✗ ${finished.error || 'orchestrator stopped'}`}
            </div>
          )}
        </>
      )}
    </div>
  );
}
