// REST wrapper for the relay's /api/* endpoints. Mirrors Android's
// RelayClient.kt signature-for-signature so the two clients talk to
// the same contract.

async function responseError(res) {
  const body = await res.text();
  const error = new Error(
    `${res.status} ${res.statusText}${body ? `: ${body}` : ''}`,
  );
  error.status = res.status;
  error.retryAfter = res.headers.get('Retry-After');
  return error;
}

export class RelayClient {
  constructor(token) {
    this.token = token;
  }

  async #request(path, init = {}) {
    const res = await fetch(path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.token}`,
        ...(init.headers || {}),
      },
    });
    if (!res.ok) {
      throw await responseError(res);
    }
    return res.json();
  }

  listHosts() {
    return this.#request('/api/hosts').then((r) => r.hosts);
  }

  async pingHost(hostId) {
    const started = performance.now();
    await this.#request(`/api/hosts/${encodeURIComponent(hostId)}/ping`);
    return Math.max(0, Math.round(performance.now() - started));
  }

  // GET /api/models: no host context, so all the relay can offer is the
  // "let codex decide" entry. Unauthenticated — needed before the user
  // has a token. Prefer listHostModels() as soon as a host is known.
  listModels() {
    return fetch('/api/models')
      .then(async (res) => {
        if (!res.ok) throw await responseError(res);
        return res.json();
      })
      .then((r) => r.models);
  }

  // Authenticated because the requested host must belong to this user.
  // Keep the response envelope: useRemotex checks its models before
  // falling back to the hostless endpoint.
  listHostModels(hostId) {
    return this.#request(`/api/hosts/${encodeURIComponent(hostId)}/models`);
  }

  // GET .../threads/{id}/preview — compact last-turns teaser served from
  // the daemon's rollout files (never codex). Safe to call on hover.
  getThreadPreview(hostId, threadId, turns = 2) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/threads/${encodeURIComponent(threadId)}/preview?turns=${turns}`,
    );
  }

  listThreads(hostId, limit = 25) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/threads?limit=${limit}`,
    ).then((r) => r.threads);
  }

  readDirectory(hostId, path) {
    const qs = new URLSearchParams({ path }).toString();
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/fs?${qs}`,
    );
  }

  mkdir(hostId, path, name) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/fs/mkdir`,
      {
        method: 'POST',
        body: JSON.stringify({ path, name }),
      },
    );
  }

  readFile(hostId, path) {
    const qs = new URLSearchParams({ path }).toString();
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/fs/read?${qs}`,
    );
  }

  deleteFile(hostId, path) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/fs/delete`,
      { method: 'POST', body: JSON.stringify({ path }) },
    );
  }

  renameFile(hostId, from, to) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/fs/rename`,
      { method: 'POST', body: JSON.stringify({ from, to }) },
    );
  }

  /** Multipart upload. Distinct from image-attach (per-turn context);
   *  this writes a real file into the workspace cwd. */
  async uploadFile(hostId, targetDir, file) {
    const fd = new FormData();
    fd.append('path', targetDir);
    fd.append('file', file, file.name);
    const res = await fetch(
      `/api/hosts/${encodeURIComponent(hostId)}/fs/upload`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${this.token}` },
        body: fd,
      },
    );
    if (!res.ok) {
      throw await responseError(res);
    }
    return res.json();
  }

  getHostTelemetry(hostId) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/telemetry`,
    );
  }

  openSession(hostId, {
    threadId = null,
    cwd = null,
  } = {}) {
    const body = { host_id: hostId };
    if (threadId) body.thread_id = threadId;
    if (cwd) body.cwd = cwd;
    return this.#request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify(body),
    }).then((r) => r.session_id);
  }
}
