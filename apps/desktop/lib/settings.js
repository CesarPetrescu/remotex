'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { normalizeRelayUrl } = require('./relay-url');

const SETTINGS_FILE = 'settings.json';

function createSettingsStore(userDataDirectory) {
  if (typeof userDataDirectory !== 'string' || !userDataDirectory) {
    throw new TypeError('A user-data directory is required.');
  }

  const settingsPath = path.join(userDataDirectory, SETTINGS_FILE);

  function load() {
    let serialized;
    try {
      serialized = fs.readFileSync(settingsPath, 'utf8');
    } catch (error) {
      if (error && error.code === 'ENOENT') {
        return { relayUrl: null };
      }
      throw error;
    }

    let saved;
    try {
      saved = JSON.parse(serialized);
    } catch {
      return { relayUrl: null };
    }

    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) {
      return { relayUrl: null };
    }

    try {
      return { relayUrl: normalizeRelayUrl(saved.relayUrl) };
    } catch {
      return { relayUrl: null };
    }
  }

  function save(relayUrl) {
    const normalized = normalizeRelayUrl(relayUrl);
    fs.mkdirSync(userDataDirectory, { recursive: true, mode: 0o700 });

    const temporaryPath = `${settingsPath}.${process.pid}.${crypto.randomUUID()}.tmp`;
    try {
      fs.writeFileSync(
        temporaryPath,
        `${JSON.stringify({ relayUrl: normalized }, null, 2)}\n`,
        { encoding: 'utf8', flag: 'wx', mode: 0o600 },
      );
      fs.renameSync(temporaryPath, settingsPath);
      fs.chmodSync(settingsPath, 0o600);
    } catch (error) {
      try {
        fs.unlinkSync(temporaryPath);
      } catch (cleanupError) {
        if (!cleanupError || cleanupError.code !== 'ENOENT') {
          // Preserve the original write error; an orphaned temp file is harmless.
        }
      }
      throw error;
    }

    return normalized;
  }

  return { load, save, settingsPath };
}

module.exports = { createSettingsStore, SETTINGS_FILE };
