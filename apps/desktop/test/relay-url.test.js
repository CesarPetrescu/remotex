'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const {
  isAllowedNavigation,
  isAllowedWebPermission,
  normalizeExternalUrl,
  normalizeRelayUrl,
} = require('../lib/relay-url');

test('normalizes secure relay origins', () => {
  assert.equal(normalizeRelayUrl('relay.example.com/path?ignored=1#hash'), 'https://relay.example.com');
  assert.equal(normalizeRelayUrl(' HTTPS://Relay.Example.com:443/app '), 'https://relay.example.com');
  assert.equal(normalizeRelayUrl('https://relay.example.com:8443'), 'https://relay.example.com:8443');
});

test('allows HTTP only for explicit loopback hosts', () => {
  assert.equal(normalizeRelayUrl('http://localhost:8080/app'), 'http://localhost:8080');
  assert.equal(normalizeRelayUrl('http://localhost.:8080'), 'http://localhost:8080');
  assert.equal(normalizeRelayUrl('http://127.0.0.1:8080'), 'http://127.0.0.1:8080');
  assert.equal(normalizeRelayUrl('http://127.25.10.3'), 'http://127.25.10.3');
  assert.equal(normalizeRelayUrl('http://[::1]:8080'), 'http://[::1]:8080');
});

test('rejects unsafe or ambiguous relay URLs', () => {
  const rejected = [
    '',
    'http://relay.example.com',
    'http://0.0.0.0:8080',
    'http://localhost.example.com:8080',
    'http://127.evil.example',
    'http://127.example.com',
    'http://127.0.0.1.evil.example',
    'ftp://relay.example.com',
    'file:///tmp/index.html',
    'javascript:alert(1)',
    'https://user:password@relay.example.com',
  ];

  for (const value of rejected) {
    assert.throws(() => normalizeRelayUrl(value), { name: 'TypeError' }, value);
  }
});

test('navigation allowlist compares parsed origins exactly', () => {
  const relay = 'https://relay.example.com';
  assert.equal(isAllowedNavigation('https://relay.example.com/dashboard', relay), true);
  assert.equal(isAllowedNavigation('https://relay.example.com:443/session/1', relay), true);
  assert.equal(isAllowedNavigation('https://relay.example.com.evil.test', relay), false);
  assert.equal(isAllowedNavigation('http://relay.example.com', relay), false);
  assert.equal(isAllowedNavigation('https://relay.example.com:8443', relay), false);
  assert.equal(isAllowedNavigation('https://user@relay.example.com', relay), false);
  assert.equal(isAllowedNavigation('javascript:alert(1)', relay), false);
});

test('external URL validation admits only credential-free HTTP(S)', () => {
  assert.equal(
    normalizeExternalUrl('https://docs.example.com/guide?q=desktop'),
    'https://docs.example.com/guide?q=desktop',
  );
  assert.equal(normalizeExternalUrl('http://example.com/path'), 'http://example.com/path');
  assert.equal(normalizeExternalUrl('mailto:help@example.com'), null);
  assert.equal(normalizeExternalUrl('file:///tmp/file'), null);
  assert.equal(normalizeExternalUrl('https://user:secret@example.com'), null);
});

test('relay permissions are minimal and exact-origin scoped', () => {
  const relay = 'https://relay.example.com';

  assert.equal(isAllowedWebPermission('notifications', relay, relay), true);
  assert.equal(
    isAllowedWebPermission('clipboard-sanitized-write', `${relay}/session/one`, relay),
    true,
  );
  assert.equal(isAllowedWebPermission('clipboard-read', relay, relay), false);
  assert.equal(
    isAllowedWebPermission('notifications', 'https://attacker.example.com', relay),
    false,
  );
});
