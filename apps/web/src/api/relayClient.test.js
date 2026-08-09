import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginErrorMessage } from '../screens/LoginScreen';
import { RelayClient } from './relayClient';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('RelayClient authentication errors', () => {
  it('exposes status and Retry-After to the login screen', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('slow down', {
      status: 429,
      statusText: 'Too Many Requests',
      headers: { 'Retry-After': '12' },
    })));

    const request = new RelayClient('secret').listHosts();
    await expect(request).rejects.toMatchObject({ status: 429, retryAfter: '12' });
    await expect(request).rejects.toThrow('slow down');
  });

  it('sends the bearer and returns verified hosts', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ hosts: [{ id: 'host-1' }] }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));
    vi.stubGlobal('fetch', fetchMock);

    await expect(new RelayClient('secret').listHosts()).resolves.toEqual([{ id: 'host-1' }]);
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer secret');
  });
});

describe('login errors', () => {
  it('turns authentication and throttling responses into actionable copy', () => {
    expect(loginErrorMessage({ status: 401 })).toContain('not accepted');
    expect(loginErrorMessage({ status: 429, retryAfter: '12' })).toContain('12 seconds');
  });
});
