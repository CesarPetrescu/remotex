// REST wrapper for the relay's /api/* endpoints. Mirrors Android's
// RelayClient.kt signature-for-signature so the two clients talk to
// the same contract.

export class RelayClient {
  constructor(token) {
    this.token = token;
  }

  setToken(t) {
    this.token = t;
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
      const body = await res.text();
      throw new Error(`${res.status} ${res.statusText}: ${body}`);
    }
    return res.json();
  }

  listHosts() {
    return this.#request('/api/hosts').then((r) => r.hosts);
  }

  // GET /api/models: relay-provided list of {id, label, hint, efforts}.
  // Unauthenticated — the model list is the same for every user, and
  // the relay needs to serve it before the user has a token.
  listModels() {
    return fetch('/api/models')
      .then((res) => {
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        return res.json();
      })
      .then((r) => r.models);
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
      const body = await res.text();
      throw new Error(`${res.status} ${res.statusText}: ${body}`);
    }
    return res.json();
  }

  getHostTelemetry(hostId) {
    return this.#request(
      `/api/hosts/${encodeURIComponent(hostId)}/telemetry`,
    );
  }

  openSession(hostId, { threadId = null, cwd = null } = {}) {
    const body = { host_id: hostId };
    if (threadId) body.thread_id = threadId;
    if (cwd) body.cwd = cwd;
    return this.#request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify(body),
    }).then((r) => r.session_id);
  }

  searchConfig() {
    return this.#request('/api/search/config');
  }

  searchChats(query, { hostId = null, limit = 20, mode = 'hybrid', threadId = null, role = null, kind = null, rerank = 'auto' } = {}) {
    const qs = new URLSearchParams({ q: query, limit: String(limit), mode });
    if (hostId) qs.set('host_id', hostId);
    if (threadId) qs.set('thread_id', threadId);
    if (role) qs.set('role', role);
    if (kind) qs.set('kind', kind);
    if (rerank !== 'auto') qs.set('rerank', rerank ? '1' : '0');
    return this.#request(`/api/search?${qs.toString()}`);
  }

  // Streaming search: emits one event per signal as it completes, then a
  // fused event, then (if on) a rerank event, then done. The onEvent
  // callback is invoked with each parsed JSON object. Returns a Promise
  // that resolves when the stream closes and an `abort` function.
  searchChatsStream(query, opts = {}) {
    const { hostId = null, limit = 20, mode = 'hybrid', threadId = null, role = null, kind = null, rerank = 'auto', onEvent, signal } = opts;
    const qs = new URLSearchParams({ q: query, limit: String(limit), mode });
    if (hostId) qs.set('host_id', hostId);
    if (threadId) qs.set('thread_id', threadId);
    if (role) qs.set('role', role);
    if (kind) qs.set('kind', kind);
    if (rerank !== 'auto') qs.set('rerank', rerank ? '1' : '0');
    const url = `/api/search/stream?${qs.toString()}`;
    const controller = signal ? null : new AbortController();
    const abortSignal = signal || controller?.signal;
    const promise = (async () => {
      const res = await fetch(url, {
        headers: { Authorization: `Bearer ${this.token}` },
        signal: abortSignal,
      });
      if (!res.ok) {
        const body = await res.text();
        throw new Error(`${res.status} ${res.statusText}: ${body}`);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let newline;
        while ((newline = buffer.indexOf('\n')) !== -1) {
          const line = buffer.slice(0, newline).trim();
          buffer = buffer.slice(newline + 1);
          if (!line) continue;
          try {
            onEvent?.(JSON.parse(line));
          } catch (err) {
            console.warn('search stream: bad JSON line', line, err);
          }
        }
      }
      const tail = buffer.trim();
      if (tail) {
        try { onEvent?.(JSON.parse(tail)); } catch { /* ignore */ }
      }
    })();
    return { promise, abort: () => controller?.abort() };
  }
}
