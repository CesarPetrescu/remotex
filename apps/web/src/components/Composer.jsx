import { useRef, useState } from 'react';
import { ModelPicker, EffortPicker, PermissionsPicker, KindPicker } from './Pickers';
import { SendOrStopButton } from './SendOrStopButton';

// Daemon-supported slash commands. Wire matches services/daemon/adapters/stdio.py.
const KNOWN_SLASHES = [
  { id: 'plan', hint: 'plan-then-act for the next turn (codex plan mode)', takesArg: false },
  { id: 'default', hint: 'clear plan mode', takesArg: false },
  { id: 'cd', hint: 'change cwd for next turns', takesArg: true, argHint: '<path>' },
  { id: 'pwd', hint: 'show current cwd', takesArg: false },
  { id: 'compact', hint: 'have codex summarise + compact the thread', takesArg: false },
];

function matchSlashes(text) {
  const q = text.slice(1).toLowerCase();
  if (!q) return KNOWN_SLASHES;
  return KNOWN_SLASHES.filter((s) => s.id.startsWith(q));
}

// Bottom dock with three rows:
//   1. pending image thumbnails (only when we have any)
//   2. model + effort + permissions chips
//   3. attach button · prompt field · send/stop
//
// Same shape as Android's ComposerBar. The pickers and send/stop
// button are dedicated components so this file stays layout-only.
export function Composer({
  connected,
  pending,
  model,
  effort,
  permissions,
  models,
  planMode,
  pendingImages,
  onModelChange,
  onEffortChange,
  onPermissionsChange,
  onSend,
  onStop,
  onAttachImage,
  onRemoveImage,
  // 4th chip — preferred kind for the next "+ New session". When the
  // current session is active, sessionKind is shown read-only since
  // you can't switch a running session in place.
  preferredKind = 'coder',
  sessionKind = null,
  onPreferredKindChange,
  onOpenOrchestrator,
  onSlashCommand,
}) {
  const [text, setText] = useState('');
  const [slashIdx, setSlashIdx] = useState(0);
  const fileInputRef = useRef(null);
  const enabled = connected && !pending;
  const canSend = enabled && (text.trim().length > 0 || pendingImages.length > 0);

  // Slash-autocomplete: when the input starts with "/" and has no
  // space yet, show the matching commands. ENTER picks the first match
  // unless arrow-keys have moved the highlight.
  const slashOpen = text.startsWith('/') && !text.includes(' ');
  const slashMatches = slashOpen ? matchSlashes(text) : [];
  const slashHighlight = slashOpen
    ? Math.min(Math.max(0, slashIdx), Math.max(0, slashMatches.length - 1))
    : 0;

  function pickSlash(cmd) {
    if (cmd.takesArg) {
      setText(`/${cmd.id} `);
    } else {
      // No-arg commands fire immediately.
      setText('');
      setSlashIdx(0);
      onSlashCommand?.(cmd.id, '');
    }
  }

  function submit() {
    if (slashOpen && slashMatches.length > 0) {
      pickSlash(slashMatches[slashHighlight]);
      return;
    }
    // Bare `/cmd args` — fire as a slash command, not a turn.
    if (text.startsWith('/')) {
      const trimmed = text.trim();
      const space = trimmed.indexOf(' ');
      const cmd = space === -1 ? trimmed.slice(1) : trimmed.slice(1, space);
      const args = space === -1 ? '' : trimmed.slice(space + 1);
      if (KNOWN_SLASHES.some((s) => s.id === cmd)) {
        setText('');
        onSlashCommand?.(cmd, args);
        return;
      }
    }
    if (!canSend) return;
    onSend(text);
    setText('');
  }

  function onKeyDown(e) {
    if (slashOpen) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSlashIdx((i) => Math.min(slashMatches.length - 1, i + 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSlashIdx((i) => Math.max(0, i - 1));
        return;
      }
      if (e.key === 'Tab') {
        e.preventDefault();
        if (slashMatches[slashHighlight]) pickSlash(slashMatches[slashHighlight]);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setText('');
        return;
      }
    }
    // Enter submits, Shift+Enter inserts a newline.
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  function onPickFiles(e) {
    const files = Array.from(e.target.files || []);
    for (const f of files) onAttachImage(f);
    e.target.value = '';
  }

  return (
    <div className="composer">
      {pendingImages.length > 0 && (
        <div className="thumb-row">
          {pendingImages.map((img, i) => (
            <div key={i} className="thumb">
              <img src={img.dataUrl} alt={img.label || ''} />
              <button
                type="button"
                className="thumb-x"
                onClick={() => onRemoveImage(i)}
                aria-label="Remove"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
      <div className="chip-row">
        <ModelPicker value={model} models={models} onChange={onModelChange} />
        <EffortPicker model={model} value={effort} models={models} onChange={onEffortChange} />
        <PermissionsPicker value={permissions} onChange={onPermissionsChange} />
        <KindPicker
          value={sessionKind || preferredKind}
          onChange={(kind) => {
            // In a live coder session the chip used to be locked, which
            // looked broken: tapping "orchestrator" did nothing because
            // you can't switch a running session's kind in place. Now
            // picking orchestrator from a coder session opens the
            // launcher modal so the user actually gets a new
            // orchestrator session instead of a silent no-op. The
            // current coder session keeps running in the background.
            if (sessionKind && sessionKind !== kind && kind === 'orchestrator') {
              onOpenOrchestrator?.();
              return;
            }
            onPreferredKindChange?.(kind);
          }}
          locked={!!sessionKind && !onOpenOrchestrator}
          lockedReason={sessionKind ? `current session is ${sessionKind}` : ''}
        />
      </div>
      {/* Plan-mode toggle chip — same hue as the kind picker but
          single-state, so a quick way to flip plan on/off without
          typing /plan. Shows persistent state via the amber border. */}
      <div className="plan-row">
        <button
          type="button"
          className={`plan-chip ${planMode ? 'on' : ''}`}
          onClick={() => onSlashCommand?.(planMode ? 'default' : 'plan', '')}
          title={planMode
            ? 'Plan mode is on — codex will plan before acting on the next turn'
            : 'Toggle plan mode for the next turn (codex /plan)'}
        >
          <span className="plan-chip-dot" />
          {planMode ? 'plan mode active — tap to clear' : 'plan mode (tap to enable for next turn)'}
        </button>
      </div>
      {slashOpen && slashMatches.length > 0 && (
        <div className="slash-popover" role="listbox">
          {slashMatches.map((cmd, i) => (
            <button
              key={cmd.id}
              type="button"
              role="option"
              className={`slash-row ${i === slashHighlight ? 'highlight' : ''}`}
              onMouseEnter={() => setSlashIdx(i)}
              onMouseDown={(e) => { e.preventDefault(); pickSlash(cmd); }}
            >
              <span className="slash-cmd">/{cmd.id}{cmd.takesArg ? ' ' + cmd.argHint : ''}</span>
              <span className="slash-hint">{cmd.hint}</span>
            </button>
          ))}
        </div>
      )}
      <div className="prompt-row">
        <button
          type="button"
          className="attach"
          disabled={!enabled}
          onClick={() => fileInputRef.current?.click()}
          aria-label="Attach image"
        >
          📎
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={onPickFiles}
        />
        <textarea
          className="prompt"
          rows={1}
          placeholder={pending ? '' : 'ask codex'}
          value={text}
          onChange={(e) => enabled && setText(e.target.value)}
          onKeyDown={onKeyDown}
          disabled={!enabled}
        />
        <SendOrStopButton
          pending={pending}
          canSend={canSend}
          onSend={submit}
          onStop={onStop}
        />
      </div>
      {/* W7: keyboard hint — only relevant when there's something to
          send. Shows up under the input row in dim mono so it doesn't
          fight for attention. */}
      {enabled && text.length > 0 && (
        <div className="composer-hint">
          <span><kbd>↵</kbd> send</span>
          <span><kbd>⇧</kbd>+<kbd>↵</kbd> newline</span>
        </div>
      )}
    </div>
  );
}
