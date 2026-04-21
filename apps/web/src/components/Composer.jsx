import { useState } from 'react';

export default function Composer({ disabled, onSend }) {
  const [text, setText] = useState('');

  function submit(e) {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText('');
  }

  return (
    <form className="composer" onSubmit={submit}>
      <input
        placeholder="ask codex…"
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={disabled}
        autoComplete="off"
      />
      <button type="submit" disabled={disabled || !text.trim()}>
        Send
      </button>
    </form>
  );
}
