// Session-tab bookkeeping for the desktop multi-session view. Pure
// functions so the LRU behavior is unit-testable without a DOM.
//
// Tabs are extra sessions beyond the primary one. At most
// MAX_ON_SCREEN sessions render at once (primary + extras); every open
// tab beyond that stays connected in the background, VS Code style, and
// focusing it swaps out the least-recently-used visible pane.

export const MAX_ON_SCREEN = 4;

/** MRU list of visible pane keys after focusing `key`. */
export function focusTab(shown, key, max) {
  return [key, ...shown.filter((k) => k !== key)].slice(0, max);
}

/** Open (or refocus) a tab. Returns the next {tabs, shown}. */
export function openTab(tabs, shown, tab, max) {
  const exists = tabs.some((t) => t.key === tab.key);
  return {
    tabs: exists ? tabs : [...tabs, tab],
    shown: focusTab(shown, tab.key, max),
  };
}

/** Close a tab entirely (socket owner unmounts with it). */
export function closeTab(tabs, shown, key) {
  return {
    tabs: tabs.filter((t) => t.key !== key),
    shown: shown.filter((k) => k !== key),
  };
}

/** Tab label: same precedence as the sidebar session rows. */
export function threadTabTitle(thread) {
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  return hasSpecificTitle ? thread.title : (thread.preview || '(no preview)');
}
