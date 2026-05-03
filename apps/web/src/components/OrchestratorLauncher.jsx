import { useState } from 'react';
import { createPortal } from 'react-dom';

// Modal launcher for a long-horizon task: collects the task description
// plus the policy snapshot every child Codex inherits. The user picks
// these BEFORE the orchestration starts because per the design we run
// children with one global approval policy — no per-step prompts.
export function OrchestratorLauncher({ open, defaults, models, onCancel, onLaunch }) {
  const [task, setTask] = useState('');
  const [model, setModel] = useState(defaults?.model || '');
  const [effort, setEffort] = useState(defaults?.effort || 'medium');
  const [permissions, setPermissions] = useState(defaults?.permissions || 'readonly');
  const [approvalPolicy, setApprovalPolicy] = useState('on-failure');

  if (!open) return null;

  const canLaunch = task.trim().length > 0;

  const submit = (e) => {
    e?.preventDefault();
    if (!canLaunch) return;
    onLaunch({
      task: task.trim(),
      model: model || null,
      effort: effort || null,
      permissions: permissions || null,
      approvalPolicy: approvalPolicy || null,
    });
  };

  const node = (
    <div className="orch-launcher-scrim" onClick={onCancel}>
      <form
        className="orch-launcher"
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
      >
        <div className="orch-launcher-title">Orchestrate a long task</div>
        <div className="orch-launcher-hint">
          The orchestrator brain will plan a DAG of subtasks, fan them out to
          child Codex agents on this host, and synthesize a final result.
          Children all use the policy you pick below.
        </div>
        <label className="orch-launcher-field">
          <span>Task</span>
          <textarea
            rows={5}
            value={task}
            onChange={(e) => setTask(e.target.value)}
            placeholder="e.g. audit the auth module for OWASP top-10 issues, file an issue per finding"
            autoFocus
          />
        </label>
        <div className="orch-launcher-row">
          <label className="orch-launcher-field">
            <span>Model</span>
            <select value={model} onChange={(e) => setModel(e.target.value)}>
              <option value="">(default)</option>
              {(models || []).map((m) => (
                <option key={m.id} value={m.id}>{m.label || m.id}</option>
              ))}
            </select>
          </label>
          <label className="orch-launcher-field">
            <span>Effort</span>
            <select value={effort} onChange={(e) => setEffort(e.target.value)}>
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
              <option value="xhigh">xhigh</option>
            </select>
          </label>
        </div>
        <div className="orch-launcher-row">
          <label className="orch-launcher-field">
            <span>Permissions (children)</span>
            <select
              value={permissions}
              onChange={(e) => setPermissions(e.target.value)}
            >
              <option value="readonly">read-only</option>
              <option value="default">workspace write</option>
              <option value="full">full access</option>
            </select>
          </label>
          <label className="orch-launcher-field">
            <span>Approval policy</span>
            <select
              value={approvalPolicy}
              onChange={(e) => setApprovalPolicy(e.target.value)}
            >
              <option value="never">never ask</option>
              <option value="on-failure">on failure only</option>
              <option value="on-request">on request</option>
            </select>
          </label>
        </div>
        <div className="orch-launcher-buttons">
          <button type="button" onClick={onCancel}>Cancel</button>
          <button type="submit" disabled={!canLaunch}>Launch</button>
        </div>
      </form>
    </div>
  );
  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}
