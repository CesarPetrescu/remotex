import { useEffect, useState } from 'react';
import { DEFAULT_APPROVAL_DECISIONS } from '../config';

const APPROVAL_ACTIONS = {
  decline: { label: 'decline', className: 'decline' },
  cancel: { label: 'cancel', className: 'cancel' },
  acceptForSession: { label: 'always', className: 'always' },
  accept: { label: 'accept', className: 'accept' },
};
const APPROVAL_ACTION_ORDER = ['decline', 'cancel', 'acceptForSession', 'accept'];

export function approvalActions(decisions) {
  const allowed = new Set(
    Array.isArray(decisions) && decisions.length > 0
      ? decisions
      : DEFAULT_APPROVAL_DECISIONS,
  );
  return APPROVAL_ACTION_ORDER
    .filter((decision) => allowed.has(decision))
    .map((decision) => ({ decision, ...APPROVAL_ACTIONS[decision] }));
}

// Renders the HEAD of each pending-prompt queue (contract F). Answering
// the head pops it and the next one takes its place — a second concurrent
// prompt never hides the first, and the header says how many are waiting.
export function PendingPromptsPanel({
  approval,
  userInput,
  approvalQueueLength = approval ? 1 : 0,
  userInputQueueLength = userInput ? 1 : 0,
  onApprovalDecision,
  onUserInputSubmit,
  onUserInputCancel,
}) {
  if (!approval && !userInput) return null;
  const count = approvalQueueLength + userInputQueueLength;
  const waiting = count - ((approval ? 1 : 0) + (userInput ? 1 : 0));
  return (
    <section className="pending-prompts-panel" aria-label="Pending Codex prompts">
      <div className="pending-prompts-head">
        <span className="pending-prompts-title">
          {count === 1 ? 'Pending prompt' : 'Pending prompts'}
        </span>
        <span className="pending-prompts-count">{count}</span>
      </div>
      {waiting > 0 && (
        <div className="pending-prompts-queued">
          {waiting} more queued — answer this one to see the next
        </div>
      )}
      {approval && (
        <ApprovalPromptCard prompt={approval} onDecision={onApprovalDecision} />
      )}
      {userInput && (
        <UserInputPromptCard
          prompt={userInput}
          onSubmit={onUserInputSubmit}
          onCancel={onUserInputCancel}
        />
      )}
    </section>
  );
}

function ApprovalPromptCard({ prompt, onDecision }) {
  const title = prompt.kind === 'command'
    ? 'Command approval'
    : prompt.kind === 'permissions'
      ? 'Permission approval'
      : 'File change approval';
  return (
    <div className="pending-prompt-card approval">
      <div className="pending-prompt-kicker">{title}</div>
      {prompt.reason && <div className="pending-prompt-text">{prompt.reason}</div>}
      {prompt.command && <pre className="pending-prompt-code">{prompt.command}</pre>}
      {prompt.permissions && (
        <pre className="pending-prompt-code">{JSON.stringify(prompt.permissions, null, 2)}</pre>
      )}
      {prompt.cwd && <div className="pending-prompt-meta">cwd: {prompt.cwd}</div>}
      <div className="pending-prompt-actions">
        {approvalActions(prompt.decisions).map(({ decision, label, className }) => (
          <button
            key={decision}
            type="button"
            className={`prompt-btn ${className}`}
            onClick={() => onDecision(decision)}
          >
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}

function UserInputPromptCard({ prompt, onSubmit, onCancel }) {
  const questions = prompt?.questions ?? [];
  const [page, setPage] = useState(0);
  const [answers, setAnswers] = useState(() => initialAnswers(questions));

  useEffect(() => {
    setPage(0);
    setAnswers(initialAnswers(questions));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prompt?.callId]);

  if (questions.length === 0) {
    return (
      <div className="pending-prompt-card input">
        <div className="pending-prompt-kicker">Codex asks</div>
        <div className="pending-prompt-text">Codex is waiting for input.</div>
        <div className="pending-prompt-actions">
          <button type="button" className="prompt-btn decline" onClick={onCancel}>
            skip
          </button>
        </div>
      </div>
    );
  }

  const current = questions[Math.min(page, questions.length - 1)];
  const isLast = page === questions.length - 1;
  const selected = answers[current.id]?.option || current.options?.[0]?.label || '';
  const notes = answers[current.id]?.notes?.[selected] || '';

  const setOption = (label) => {
    setAnswers((prev) => ({
      ...prev,
      [current.id]: {
        ...(prev[current.id] || { notes: {} }),
        option: label,
      },
    }));
  };

  const setNotes = (text) => {
    const option = selected || current.options?.[0]?.label || '';
    setAnswers((prev) => ({
      ...prev,
      [current.id]: {
        option,
        notes: { ...(prev[current.id]?.notes || {}), [option]: text },
      },
    }));
  };

  const submit = () => onSubmit(buildWire(questions, answers));

  return (
    <form
      className="pending-prompt-card input"
      onSubmit={(e) => {
        e.preventDefault();
        if (isLast) submit();
        else setPage((p) => Math.min(p + 1, questions.length - 1));
      }}
    >
      <div className="pending-prompt-kicker">
        Codex asks
        {questions.length > 1 && (
          <span className="pending-prompt-page">{page + 1}/{questions.length}</span>
        )}
      </div>
      {current.header && <div className="pending-prompt-header">{current.header}</div>}
      {current.question && <div className="pending-prompt-text">{current.question}</div>}

      {Array.isArray(current.options) && current.options.length > 0 && (
        <div className="pending-prompt-options" role="radiogroup">
          {current.options.map((option) => (
            <label
              key={option.label}
              className={`pending-prompt-option${selected === option.label ? ' selected' : ''}`}
            >
              <input
                type="radio"
                name={`prompt-${prompt.callId}-${current.id}`}
                checked={selected === option.label}
                onChange={() => setOption(option.label)}
              />
              <span className="pending-prompt-option-label">{option.label}</span>
              {option.description && (
                <span className="pending-prompt-option-desc">{option.description}</span>
              )}
            </label>
          ))}
        </div>
      )}

      {current.isSecret === true ? (
        <input
          type="password"
          className="pending-prompt-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="type your secret answer"
          aria-label={current.header || current.question || 'Secret answer'}
          autoComplete="new-password"
          spellCheck={false}
        />
      ) : (
        <textarea
          className="pending-prompt-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder={current.options?.length ? 'optional notes' : 'type your answer'}
          aria-label={current.header || current.question || 'Answer'}
          rows={current.options?.length ? 2 : 4}
          spellCheck={false}
        />
      )}

      <div className="pending-prompt-actions">
        <button type="button" className="prompt-btn decline" onClick={onCancel}>
          skip
        </button>
        {questions.length > 1 && page > 0 && (
          <button type="button" className="prompt-btn" onClick={() => setPage((p) => Math.max(0, p - 1))}>
            back
          </button>
        )}
        <button type="submit" className="prompt-btn accept">
          {isLast ? 'submit' : 'next'}
        </button>
      </div>
    </form>
  );
}

function initialAnswers(questions) {
  const out = {};
  for (const q of questions) {
    out[q.id] = {
      option: q.options?.[0]?.label || null,
      notes: {},
    };
  }
  return out;
}

function buildWire(questions, answers) {
  const out = {};
  for (const q of questions) {
    const answer = answers[q.id] || { option: null, notes: {} };
    const values = [];
    if (answer.option) values.push(answer.option);
    const note = answer.notes?.[answer.option] || '';
    if (note.trim()) values.push(note.trim());
    out[q.id] = values.length > 0 ? values : ['skipped'];
  }
  return out;
}
