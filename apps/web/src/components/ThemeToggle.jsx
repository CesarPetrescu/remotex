import { useState } from 'react';
import { nextTheme, resolvedTheme, toggleTheme } from '../util/theme';

const THEME_NAME = { dark: 'Dark', light: 'White', contrast: 'High contrast' };
const THEME_MARK = { dark: 'D', light: 'W', contrast: 'HC' };

// Theme switch. Shown in the dashboard header and floated on the
// login screen, so the theme is reachable before sign-in.
export function ThemeToggle({ className = '' }) {
  const [theme, setTheme] = useState(resolvedTheme);
  const next = nextTheme(theme);
  return (
    <button
      type="button"
      className={`icon-button theme-toggle ${className}`.trim()}
      onClick={() => setTheme(toggleTheme())}
      aria-label={`Theme: ${THEME_NAME[theme]}. Switch to ${THEME_NAME[next]}`}
      title={`${THEME_NAME[theme]} theme · next: ${THEME_NAME[next]}`}
    >
      <span aria-hidden="true">{THEME_MARK[theme]}</span>
    </button>
  );
}
