import { useEffect, useRef, useState } from 'react';
import { RelayClient } from '../api/relayClient';
import {
  clearToken,
  loadRemember,
  loadToken,
  saveToken,
} from '../util/tokenStorage';
import { ThemeToggle } from '../components/ThemeToggle';

export function loginErrorMessage(error) {
  if (error?.status === 401) return 'That access token was not accepted.';
  if (error?.status === 429) {
    const retryAfter = String(error.retryAfter || '').trim();
    if (/^\d+$/.test(retryAfter)) {
      return `Too many attempts. Try again in ${retryAfter} seconds.`;
    }
    return retryAfter
      ? `Too many attempts. Try again after ${retryAfter}.`
      : 'Too many attempts. Try again shortly.';
  }
  return 'Could not reach Remotex. Check your connection and try again.';
}

export function LoginScreen({ onAuthenticated }) {
  const [initialToken] = useState(loadToken);
  const [initialRemember] = useState(loadRemember);
  const [token, setToken] = useState(initialToken);
  const [remember, setRemember] = useState(initialRemember);
  const [reveal, setReveal] = useState(false);
  const [loading, setLoading] = useState(Boolean(initialToken));
  const [error, setError] = useState('');
  const autoCheckRef = useRef(null);

  useEffect(() => {
    if (!initialToken) return undefined;

    // React StrictMode runs effects twice in development. Reuse the same
    // verification request so a saved token never burns two login attempts.
    autoCheckRef.current ||= new RelayClient(initialToken).listHosts();
    let active = true;
    autoCheckRef.current
      .then((hosts) => {
        if (active) onAuthenticated({ token: initialToken, remember: initialRemember, hosts });
      })
      .catch((cause) => {
        if (!active) return;
        if (cause?.status === 401) {
          clearToken();
          setToken('');
        }
        setError(loginErrorMessage(cause));
        setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [initialRemember, initialToken, onAuthenticated]);

  const submit = async (event) => {
    event.preventDefault();
    const candidate = token.trim();
    if (!candidate) {
      setError('Enter an access token.');
      return;
    }

    setLoading(true);
    setError('');
    try {
      const hosts = await new RelayClient(candidate).listHosts();
      saveToken(candidate, remember);
      onAuthenticated({ token: candidate, remember, hosts });
    } catch (cause) {
      if (cause?.status === 401) clearToken();
      setError(loginErrorMessage(cause));
      setLoading(false);
    }
  };

  return (
    <main className="login-shell">
      <ThemeToggle className="login-theme-toggle" />
      <section className="login-card" aria-labelledby="login-title">
        <div className="login-brand" aria-hidden="true">
          <img className="brand-logo" src="/favicon-192.png" alt="" />
          <span>REMOTEX</span>
        </div>
        <p className="login-eyebrow">Remote Codex control plane</p>
        <h1 id="login-title">Sign in</h1>
        <p className="login-intro">
          Use the access token issued by your Remotex relay.
        </p>

        <form className="login-form" onSubmit={submit}>
          <label htmlFor="login-token">Access token</label>
          <div className="login-token-row">
            <input
              id="login-token"
              type={reveal ? 'text' : 'password'}
              value={token}
              onChange={(event) => setToken(event.target.value)}
              autoComplete="current-password"
              autoCapitalize="none"
              spellCheck={false}
              disabled={loading}
              required
              autoFocus={!initialToken}
            />
            <button
              type="button"
              className="btn-sm"
              onClick={() => setReveal((shown) => !shown)}
              aria-controls="login-token"
              aria-pressed={reveal}
              disabled={loading}
            >
              {reveal ? 'Hide' : 'Show'}
            </button>
          </div>

          <label className="login-remember">
            <input
              type="checkbox"
              checked={remember}
              onChange={(event) => setRemember(event.target.checked)}
              disabled={loading}
            />
            <span>Remember on this device</span>
          </label>
          <p className="login-storage-hint">
            {remember
              ? 'Stored in this browser until you sign out.'
              : 'Kept for this tab only and removed when the tab closes.'}
          </p>

          {error && <p className="login-error" role="alert" aria-live="polite">{error}</p>}
          {loading && !error && (
            <p className="login-status" role="status" aria-live="polite">
              Checking access…
            </p>
          )}

          <button type="submit" className="btn-primary login-submit" disabled={loading}>
            {loading ? 'Checking…' : 'Sign in'}
          </button>
        </form>
      </section>
    </main>
  );
}
