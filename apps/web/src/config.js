// Shared constants: screens, session status, and the option lists for
// the model / reasoning effort / permissions pickers.
//
// There is no model list in this file, on purpose. Models and their
// supported reasoning efforts are fetched from the selected host's codex
// (`GET /api/hosts/{id}/models` → `model/list`), because availability
// depends on that host's codex version and signed-in account. A copy
// shipped in the client goes stale silently — this file used to name
// `gpt-5.5` as "newest frontier" while hosts served `gpt-5.6-*`, and its
// effort list omitted `max`/`ultra` so they were unselectable. See I-002.

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

// Codex omits `decisions` when every approval response is allowed. Keep the
// fallback in one web-client constant so normalization and rendering cannot
// drift apart.
export const DEFAULT_APPROVAL_DECISIONS = [
  'accept',
  'acceptForSession',
  'decline',
  'cancel',
];

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

// Last-resort effort names, for rendering a picker before any host has
// answered. Codex reports the real per-model set; it is the authority.
export const ALL_EFFORTS = [EFFORT_DEFAULT, 'low', 'medium', 'high', 'xhigh'];

// The only option we can offer without asking a host: id '' means "send no
// model override", so codex uses its own default. Real entries arrive from
// listHostModels() and replace this in state.
export const FALLBACK_MODEL_OPTIONS = [
  { id: '', label: 'default', hint: 'codex picks', efforts: ALL_EFFORTS },
];

export const MODEL_OPTIONS = FALLBACK_MODEL_OPTIONS;

export function effortsFor(modelId, modelOptions = MODEL_OPTIONS) {
  return modelOptions.find((m) => m.id === modelId)?.efforts ?? ALL_EFFORTS;
}
