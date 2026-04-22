// Shared constants: screens, session status, and the option lists for
// the model / reasoning effort / permissions pickers. These are the
// single source of truth the Android app uses too (in Kotlin) — kept in
// sync here so the two clients always show the same choices.

export const SCREENS = {
  Hosts: 'hosts',
  Threads: 'threads',
  Files: 'files',
  Session: 'session',
  Search: 'search',
};

export const STATUS = {
  Idle: 'idle',
  Opening: 'opening',
  Connecting: 'connecting',
  Connected: 'connected',
  Disconnected: 'disconnected',
  Error: 'error',
};

export const PERMISSIONS = [
  { id: 'default', label: 'Default', hint: 'ask for internet + outside writes' },
  { id: 'full', label: 'Full Access', hint: 'no prompts — use with caution' },
  { id: 'readonly', label: 'Read Only', hint: 'codex can look but not touch' },
];

export const EFFORT_DEFAULT = '';
export const ALL_EFFORTS = [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'];

// Models visible in `codex 0.122.0`. Effort list is per-model so
// `gpt-5.1-codex-mini` only shows medium/high (what codex accepts).
export const MODEL_OPTIONS = [
  { id: '', label: 'default', hint: 'codex picks', efforts: ALL_EFFORTS },
  { id: 'gpt-5.4', label: 'gpt-5.4', hint: 'latest frontier (default)',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.4-mini', label: 'gpt-5.4 · mini', hint: 'smaller frontier',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.3-codex', label: 'gpt-5.3 · codex', hint: 'codex-optimized',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.3-codex-spark', label: 'gpt-5.3 · codex spark', hint: 'ultra-fast coding',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.2', label: 'gpt-5.2', hint: 'long-running agents',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.2-codex', label: 'gpt-5.2 · codex', hint: 'codex-optimized',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.1-codex-max', label: 'gpt-5.1 · codex max', hint: 'deep reasoning',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.1-codex-mini', label: 'gpt-5.1 · codex mini', hint: 'cheaper/faster',
    efforts: [EFFORT_DEFAULT, 'medium', 'high'] },
];

export function effortsFor(modelId) {
  return MODEL_OPTIONS.find((m) => m.id === modelId)?.efforts ?? ALL_EFFORTS;
}
