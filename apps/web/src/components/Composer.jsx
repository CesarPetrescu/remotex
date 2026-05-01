import { useRef, useState } from 'react';
import { ModelPicker, EffortPicker, PermissionsPicker } from './Pickers';
import { SendOrStopButton } from './SendOrStopButton';

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
}) {
  const [text, setText] = useState('');
  const fileInputRef = useRef(null);
  const enabled = connected && !pending;
  const canSend = enabled && (text.trim().length > 0 || pendingImages.length > 0);

  function submit() {
    if (!canSend) return;
    onSend(text);
    setText('');
  }

  function onKeyDown(e) {
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
      </div>
      {planMode && (
        <div className="plan-hint">plan mode active — /default to clear</div>
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
