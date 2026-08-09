import { useState } from 'react';
import { resolvedTheme, toggleTheme } from '../util/theme';

// Light/dark switch. Shown in the dashboard header and floated on the
// login screen, so the theme is reachable before sign-in.
export function ThemeToggle({ className = '' }) {
  const [theme, setTheme] = useState(resolvedTheme);
  const dark = theme === 'dark';
  return (
    <button
      type="button"
      className={`icon-button theme-toggle ${className}`.trim()}
      onClick={() => setTheme(toggleTheme())}
      aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'}
      title={dark ? 'Light theme' : 'Dark theme'}
    >
      <span aria-hidden="true">{dark ? '☀' : '☾'}</span>
    </button>
  );
}
