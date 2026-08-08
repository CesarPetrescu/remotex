// Shared constants: screens, session status, and the option lists for
// the model / reasoning effort / permissions pickers.
//
// MODEL_OPTIONS is now a fallback only. The canonical list is whatever
// the selected host's codex reports — GET /api/hosts/{host_id}/models.
// useRemotex asks the host first, falls back to the relay's static list
// at GET /api/models, and only then to the embedded array below.
// Bumping a model means editing services/relay/models.py (and mirroring
// it here for the offline case) — no other client edit needed.

export const SCREENS = {
  Hosts: 'hosts',
  Threads: 'threads',
  Files: 'files',
  Session: 'session',
};

export const STATUS = {
  Idle: 'idle',
  Opening: 'opening',
  Connecting: 'connecting',
  Connected: 'connected',
  Disconnected: 'disconnected',
  Error: 'error',
};

// Single client-side ceiling for anything that travels as bytes — image
// attachments (base64 inside a turn-start frame) and workspace uploads.
// Mirrors the relay/daemon REMOTEX_MAX_FILE_BYTES default (25 MB); the
// relay sizes its WebSocket max_msg_size off the same number, so going
// over here means a rejected request or a dropped socket. Overridable at
// build time with VITE_REMOTEX_MAX_FILE_BYTES for deployments that
// raised the server-side limit.
const envMaxFileBytes = Number(import.meta.env?.VITE_REMOTEX_MAX_FILE_BYTES);
export const MAX_FILE_BYTES = Number.isFinite(envMaxFileBytes) && envMaxFileBytes > 0
  ? envMaxFileBytes
  : 25 * 1024 * 1024;

export function formatBytes(n) {
  if (!Number.isFinite(n)) return '—';
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  if (n >= 1024) return `${Math.round(n / 1024)} KB`;
  return `${n} B`;
}

export const PERMISSIONS = [
  { id: 'default', label: 'Default', hint: 'ask for internet + outside writes' },
  { id: 'full', label: 'Full Access', hint: 'no prompts — use with caution' },
  { id: 'readonly', label: 'Read Only', hint: 'codex can look but not touch' },
];

export const EFFORT_DEFAULT = '';
export const ALL_EFFORTS = [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'];
// gpt-5.6 accepts two deeper levels than the 5.x line does.
const DEEP_EFFORTS = [...ALL_EFFORTS, 'max', 'ultra'];
const MAX_EFFORTS = [...ALL_EFFORTS, 'max'];

// Fallback model list. Used only when both GET /api/hosts/{id}/models and
// GET /api/models fail. Keep it in sync with services/relay/models.py — it
// is the literal copy that ships for offline use.
export const FALLBACK_MODEL_OPTIONS = [
  { id: '', label: 'default', hint: 'codex picks', efforts: ALL_EFFORTS },
  { id: 'gpt-5.6-sol', label: 'gpt-5.6 · sol', hint: 'latest frontier agentic coding',
    efforts: DEEP_EFFORTS },
  { id: 'gpt-5.6-terra', label: 'gpt-5.6 · terra', hint: 'balanced everyday work',
    efforts: DEEP_EFFORTS },
  { id: 'gpt-5.6-luna', label: 'gpt-5.6 · luna', hint: 'fast and affordable',
    efforts: MAX_EFFORTS },
  { id: 'gpt-5.5', label: 'gpt-5.5', hint: 'frontier',
    efforts: ALL_EFFORTS },
  { id: 'gpt-5.2', label: 'gpt-5.2', hint: 'long-running agents',
    efforts: ALL_EFFORTS },
];

// Backwards-compatible name. Existing imports continue to work; the
// list resolves to the fallback until the relay's response replaces it
// via the MODEL_OPTIONS reducer action in useRemotex.
export const MODEL_OPTIONS = FALLBACK_MODEL_OPTIONS;

export function effortsFor(modelId, modelOptions = MODEL_OPTIONS) {
  return modelOptions.find((m) => m.id === modelId)?.efforts ?? ALL_EFFORTS;
}
