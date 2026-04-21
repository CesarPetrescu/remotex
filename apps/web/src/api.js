export class RelayApi {
  constructor(token) {
    this.token = token;
  }

  setToken(token) {
    this.token = token;
  }

  async #request(path, opts = {}) {
    const res = await fetch(path, {
      ...opts,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.token}`,
        ...(opts.headers || {}),
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

  openSession(hostId) {
    return this.#request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ host_id: hostId }),
    }).then((r) => r.session_id);
  }
}
