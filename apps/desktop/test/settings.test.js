'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { createSettingsStore } = require('../lib/settings');

function withTemporaryDirectory(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'remotex-desktop-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

test('missing settings start with no relay', (t) => {
  const directory = withTemporaryDirectory(t);
  const store = createSettingsStore(path.join(directory, 'user-data'));
  assert.deepEqual(store.load(), { relayUrl: null });
});

test('settings persist only the normalized relay origin', (t) => {
  const directory = withTemporaryDirectory(t);
  const userData = path.join(directory, 'user-data');
  const store = createSettingsStore(userData);

  assert.equal(store.save(' Relay.Example.com/app?from=desktop '), 'https://relay.example.com');
  assert.deepEqual(store.load(), { relayUrl: 'https://relay.example.com' });
  assert.deepEqual(JSON.parse(fs.readFileSync(store.settingsPath, 'utf8')), {
    relayUrl: 'https://relay.example.com',
  });

  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(store.settingsPath).mode & 0o777, 0o600);
  }

  assert.deepEqual(
    fs.readdirSync(userData).filter((name) => name.endsWith('.tmp')),
    [],
  );
});

test('malformed or unsafe saved settings return to first-run state', (t) => {
  const directory = withTemporaryDirectory(t);
  const userData = path.join(directory, 'user-data');
  fs.mkdirSync(userData);
  const store = createSettingsStore(userData);

  fs.writeFileSync(store.settingsPath, '{bad json', 'utf8');
  assert.deepEqual(store.load(), { relayUrl: null });

  fs.writeFileSync(store.settingsPath, JSON.stringify({ relayUrl: 'http://relay.example.com' }), 'utf8');
  assert.deepEqual(store.load(), { relayUrl: null });
});

test('subsequent saves atomically replace the selected relay', (t) => {
  const directory = withTemporaryDirectory(t);
  const userData = path.join(directory, 'user-data');
  const store = createSettingsStore(userData);

  store.save('https://one.example.com');
  store.save('http://127.0.0.1:8080/session/old');

  assert.deepEqual(store.load(), { relayUrl: 'http://127.0.0.1:8080' });
  assert.deepEqual(fs.readdirSync(userData), ['settings.json']);
});
