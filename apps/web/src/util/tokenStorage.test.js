import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  clearToken,
  loadRemember,
  loadToken,
  saveToken,
} from './tokenStorage';

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
  };
}

const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const originalSessionStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');

function installStorage(name, value) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

beforeEach(() => {
  installStorage('localStorage', memoryStorage());
  installStorage('sessionStorage', memoryStorage());
});

afterEach(() => {
  if (originalLocalStorage) Object.defineProperty(globalThis, 'localStorage', originalLocalStorage);
  else delete globalThis.localStorage;
  if (originalSessionStorage) Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage);
  else delete globalThis.sessionStorage;
});

describe('token storage', () => {
  it('has no public demo-token fallback', () => {
    expect(loadToken()).toBe('');
  });

  it('keeps a verified token in only the selected store', () => {
    saveToken('persistent', true);
    expect(localStorage.getItem('remotex.userToken')).toBe('persistent');
    expect(sessionStorage.getItem('remotex.userToken')).toBeNull();

    saveToken('tab-only', false);
    expect(localStorage.getItem('remotex.userToken')).toBeNull();
    expect(sessionStorage.getItem('remotex.userToken')).toBe('tab-only');
    expect(loadToken()).toBe('tab-only');
    expect(loadRemember()).toBe(false);
  });

  it('signs out of both persistence modes', () => {
    saveToken('persistent', true);
    sessionStorage.setItem('remotex.userToken', 'stale');
    clearToken();

    expect(localStorage.getItem('remotex.userToken')).toBeNull();
    expect(sessionStorage.getItem('remotex.userToken')).toBeNull();
    expect(loadRemember()).toBe(true);
  });
});
