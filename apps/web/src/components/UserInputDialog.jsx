import { useEffect, useMemo, useState } from 'react';

/**
 * Renders codex's request_user_input prompt as a modal dialog.
 * Mirrors the TUI overlay (codex-rs/docs/tui-request-user-input.md):
 *   - One question at a time, with prev/next paging.
 *   - Each question can have options (radio) and notes (textarea).
 *   - Notes are kept per-option so switching the selection preserves
 *     what the user typed for each.
 *   - "Skip all" submits an empty answers map (codex marks every
 *     question skipped).
 *
 * Wire shape sent back via cancel/submit:
 *   { questionId: ["selected label", "notes if any"] }
 */
export function UserInputDialog({ prompt, onSubmit, onCancel }) {
  const questions = prompt?.questions ?? [];
  const [page, setPage] = useState(0);
  // answers[qid] = { option: <label or null>, notes: { <label>: text } }
  const [answers, setAnswers] = useState(() => initialAnswers(questions));

  // Reset state when a fresh prompt arrives.
  useEffect(() => {
    setPage(0);
    setAnswers(initialAnswers(questions));
    // Only re-init when the prompt's call_id changes — questions array
    // identity flips per render even when the prompt is the same.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prompt?.callId]);

  const current = questions[page];
  const isLast = page === questions.length - 1;

  if (!prompt || !current) return null;

  const setOption = (label) => {
    setAnswers((prev) => ({
      ...prev,
      [current.id]: { ...(prev[current.id] || { notes: {} }), option: label },
    }));
  };
  const setNotes = (text) => {
    const opt = (answers[current.id]?.option) || (current.options?.[0]?.label) || '';
    setAnswers((prev) => ({
      ...prev,
      [current.id]: {
        option: opt,
        notes: { ...(prev[current.id]?.notes || {}), [opt]: text },
      },
    }));
  };

  const submit = () => {
    onSubmit(buildWire(questions, answers));
  };

  const opt = answers[current.id]?.option || current.options?.[0]?.label || '';
  const notes = answers[current.id]?.notes?.[opt] || '';

  return (
    <div className="ui-input-scrim" onClick={onCancel}>
      <form
        className="ui-input-dialog"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => { e.preventDefault(); isLast ? submit() : setPage(page + 1); }}
      >
        <div className="ui-input-head">
          <span className="ui-input-eyebrow">CODEX ASKS</span>
          <span className="ui-input-page">
            {questions.length > 1 ? `${page + 1} / ${questions.length}` : ''}
          </span>
        </div>

        {current.header && <div className="ui-input-q-header">{current.header}</div>}
        <div className="ui-input-q-body">{current.question}</div>

        {Array.isArray(current.options) && current.options.length > 0 && (
          <div className="ui-input-options" role="radiogroup">
            {current.options.map((o) => (
              <label
                key={o.label}
                className={`ui-input-option ${opt === o.label ? 'selected' : ''}`}
              >
                <input
                  type="radio"
                  name={`q-${current.id}`}
                  checked={opt === o.label}
                  onChange={() => setOption(o.label)}
                />
                <span className="ui-input-option-label">{o.label}</span>
                {o.description && (
                  <span className="ui-input-option-desc">{o.description}</span>
                )}
              </label>
            ))}
          </div>
        )}

        <textarea
          className="ui-input-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder={
            current.is_secret || current.isSecret
              ? 'enter answer (won\'t be echoed in transcripts)'
              : (current.options?.length
                ? 'optional notes for this choice'
                : 'type your answer')
          }
          rows={current.options?.length ? 2 : 4}
          spellCheck={false}
        />

        <div className="ui-input-actions">
          <button type="button" className="ui-input-skip" onClick={onCancel}>
            Skip all
          </button>
          {questions.length > 1 && page > 0 && (
            <button
              type="button"
              className="ui-input-prev"
              onClick={() => setPage(page - 1)}
            >Back</button>
          )}
          <button type="submit" className="ui-input-next">
            {isLast ? 'Submit' : 'Next →'}
          </button>
        </div>
      </form>
    </div>
  );
}

function initialAnswers(questions) {
  const out = {};
  for (const q of questions) {
    const firstOpt = q.options?.[0]?.label || null;
    out[q.id] = { option: firstOpt, notes: {} };
  }
  return out;
}

function buildWire(questions, answers) {
  const out = {};
  for (const q of questions) {
    const a = answers[q.id] || { option: null, notes: {} };
    const arr = [];
    if (a.option) arr.push(a.option);
    const text = a.notes[a.option] || '';
    if (text.trim()) arr.push(text);
    if (arr.length === 0) {
      // No selection AND no notes → skipped. Codex's TUI sends
      // ["skipped"] in this case; keep the same convention.
      arr.push('skipped');
    }
    out[q.id] = arr;
  }
  return out;
}
