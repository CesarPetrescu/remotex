import { useEffect, useState } from 'react';

export function GoalPanel({
  goal,
  connected,
  onSetGoal,
  onPauseGoal,
  onResumeGoal,
  onClearGoal,
  onRefreshGoal,
}) {
  const [objective, setObjective] = useState('');
  const [tokenBudget, setTokenBudget] = useState('');

  useEffect(() => {
    setObjective(goal?.objective || '');
    setTokenBudget(Number.isFinite(goal?.token_budget) ? String(goal.token_budget) : '');
  }, [goal]);

  const status = goal?.status || 'none';
  const canSubmit = connected && objective.trim().length > 0;
  const budgetValue = parseBudget(tokenBudget);

  function submit(e) {
    e.preventDefault();
    if (!canSubmit) return;
    onSetGoal?.({
      objective: objective.trim(),
      tokenBudget: budgetValue,
    });
  }

  return (
    <section className="goal-panel" aria-label="Native Codex goal">
      <div className="goal-panel-head">
        <span className="goal-panel-eyebrow">Codex Goal</span>
        <button
          type="button"
          className="prompt-btn"
          onClick={onRefreshGoal}
          disabled={!connected}
        >
          refresh
        </button>
      </div>

      {goal ? (
        <div className={`goal-current goal-current-${String(status).toLowerCase()}`}>
          <div className="goal-current-row">
            <span className="goal-status">{formatStatus(status)}</span>
            <span className="goal-usage">{formatUsage(goal)}</span>
          </div>
          <div className="goal-objective" title={goal.objective}>
            {goal.objective || 'No objective text'}
          </div>
          <div className="goal-time">{formatTime(goal.time_used_seconds)}</div>
        </div>
      ) : (
        <div className="goal-empty">No native Codex goal set for this thread.</div>
      )}

      <form className="goal-form" onSubmit={submit}>
        <label className="goal-field">
          <span>Objective</span>
          <textarea
            value={objective}
            onChange={(e) => setObjective(e.target.value)}
            disabled={!connected}
            rows={4}
            placeholder="Describe the concrete outcome Codex should keep pursuing"
          />
        </label>
        <label className="goal-field">
          <span>Token budget</span>
          <input
            type="number"
            min="1"
            step="1"
            value={tokenBudget}
            onChange={(e) => setTokenBudget(e.target.value)}
            disabled={!connected}
            placeholder="optional"
          />
        </label>
        <div className="goal-actions">
          <button type="submit" className="prompt-btn accept" disabled={!canSubmit}>
            set active
          </button>
          <button
            type="button"
            className="prompt-btn"
            onClick={onPauseGoal}
            disabled={!connected || !goal || status !== 'active'}
          >
            pause
          </button>
          <button
            type="button"
            className="prompt-btn"
            onClick={onResumeGoal}
            disabled={!connected || !goal || status === 'active'}
          >
            resume
          </button>
          <button
            type="button"
            className="prompt-btn decline"
            onClick={onClearGoal}
            disabled={!connected || !goal}
          >
            clear
          </button>
        </div>
      </form>
    </section>
  );
}

function parseBudget(value) {
  const trimmed = String(value || '').trim();
  if (!trimmed) return undefined;
  const parsed = Number.parseInt(trimmed, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

function formatStatus(status) {
  if (status === 'budgetLimited') return 'budget limited';
  return status || 'none';
}

function formatUsage(goal) {
  const used = Number.isFinite(goal?.tokens_used) ? goal.tokens_used : 0;
  const budget = Number.isFinite(goal?.token_budget) ? goal.token_budget : 0;
  if (budget > 0) return `${compact(used)} / ${compact(budget)} tokens`;
  return `${compact(used)} tokens`;
}

function formatTime(seconds) {
  const value = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
  if (value < 60) return `${value}s elapsed`;
  if (value < 3600) return `${Math.floor(value / 60)}m elapsed`;
  return `${Math.floor(value / 3600)}h ${Math.floor((value % 3600) / 60)}m elapsed`;
}

function compact(n) {
  if (n < 1000) return String(n);
  if (n < 1000000) return `${Math.round(n / 1000)}K`;
  return `${(n / 1000000).toFixed(1)}M`;
}
