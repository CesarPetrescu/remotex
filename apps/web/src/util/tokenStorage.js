// Where the bearer token lives.
//
// "Remember on this device" (default ON, so the existing UX doesn't
// regress) keeps the token in localStorage — it survives a browser
// restart. Turned OFF, the token goes to sessionStorage instead and dies
// with the tab, which is what you want on a shared or borrowed machine.
//
// This is mitigation, not a fix: a long-lived bearer token readable by
// any script on the origin is the actual problem, and only a real OIDC
// flow (short-lived tokens + refresh) solves it.

const TOKEN_KEY = 'remotex.userToken';
const REMEMBER_KEY = 'remotex.rememberToken';
export const DEFAULT_TOKEN = 'demo-user-token';

function safeGet(store, key) {
  try {
    return store.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(store, key, value) {
  try {
    store.setItem(key, value);
  } catch {
    // private mode / storage disabled — the in-memory token still works
    // for this tab.
  }
}

function safeRemove(store, key) {
  try {
    store.removeItem(key);
  } catch {
    // ignore
  }
}

/** Default ON — only an explicit "false" opts out. */
export function loadRemember() {
  try {
    return localStorage.getItem(REMEMBER_KEY) !== 'false';
  } catch {
    return true;
  }
}

export function loadToken() {
  try {
    // Read both stores regardless of the flag: a token parked in the
    // other one (flag flipped in another tab) is still the user's token.
    return (
      safeGet(loadRemember() ? localStorage : sessionStorage, TOKEN_KEY) ||
      safeGet(sessionStorage, TOKEN_KEY) ||
      safeGet(localStorage, TOKEN_KEY) ||
      DEFAULT_TOKEN
    );
  } catch {
    return DEFAULT_TOKEN;
  }
}

/** Write the token to the chosen store and purge it from the other one. */
export function saveToken(token, remember) {
  try {
    const keep = remember ? localStorage : sessionStorage;
    const drop = remember ? sessionStorage : localStorage;
    safeSet(keep, TOKEN_KEY, token);
    safeRemove(drop, TOKEN_KEY);
    // The flag itself is a preference, not a credential — it always
    // lives in localStorage so "don't remember" survives a restart.
    safeSet(localStorage, REMEMBER_KEY, String(Boolean(remember)));
  } catch {
    // ignore
  }
}
