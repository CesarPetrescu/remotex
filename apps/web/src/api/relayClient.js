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

  openSession(hostId, { threadId = null, cwd = null } = {}) {
    const body = { host_id: hostId };
    if (threadId) body.thread_id = threadId;
    if (cwd) body.cwd = cwd;
    return this.#request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify(body),
    }).then((r) => r.session_id);
  }
}
