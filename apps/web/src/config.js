// Shared constants: screens, session status, and the option lists for
// the model / reasoning effort / permissions pickers.
//
// MODEL_OPTIONS is now a fallback only. The relay serves the canonical
// list at GET /api/models; useRemotex fetches it on first paint and
// cached the result in state. When the fetch fails (offline / ancient
// relay), the embedded array below is used. Bumping a model now means
// editing services/relay/models.py — no client edit needed.

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

// Fallback model list. Used only when GET /api/models fails. Keep it in
// sync with services/relay/models.py — it is the literal copy that ships
// for offline use.
export const FALLBACK_MODEL_OPTIONS = [
  { id: '', label: 'default', hint: 'codex picks', efforts: ALL_EFFORTS },
  { id: 'gpt-5.5', label: 'gpt-5.5', hint: 'newest frontier',
    efforts: [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'] },
  { id: 'gpt-5.4', label: 'gpt-5.4', hint: 'frontier',
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

// Backwards-compatible name. Existing imports continue to work; the
// list resolves to the fallback until the relay's response replaces
// it via setModelOptions() from useRemotex.
export const MODEL_OPTIONS = FALLBACK_MODEL_OPTIONS;

export function effortsFor(modelId, modelOptions = MODEL_OPTIONS) {
  return modelOptions.find((m) => m.id === modelId)?.efforts ?? ALL_EFFORTS;
}
