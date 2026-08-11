'use strict';

const net = require('node:net');

const HTTP_PROTOCOL = 'http:';
const HTTPS_PROTOCOL = 'https:';
const SCHEME_PATTERN = /^[a-zA-Z][a-zA-Z\d+.-]*:/;
const ALLOWED_WEB_PERMISSIONS = new Set([
  'clipboard-sanitized-write',
  'notifications',
]);

function parseUrl(value, { defaultToHttps = false } = {}) {
  if (typeof value !== 'string') {
    throw new TypeError('Relay URL must be a string.');
  }

  const trimmed = value.trim();
  if (!trimmed) {
    throw new TypeError('Enter a relay URL.');
  }

  const candidate = defaultToHttps && !SCHEME_PATTERN.test(trimmed)
    ? `https://${trimmed}`
    : trimmed;

  let parsed;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new TypeError('Enter a valid relay URL.');
  }

  if (parsed.protocol !== HTTP_PROTOCOL && parsed.protocol !== HTTPS_PROTOCOL) {
    throw new TypeError('Relay URLs must use HTTPS, or HTTP on this computer.');
  }

  if (parsed.username || parsed.password) {
    throw new TypeError('Relay URLs cannot include a username or password.');
  }

  return parsed;
}

function isLoopbackHostname(hostname) {
  const normalized = hostname.toLowerCase();
  if (normalized === 'localhost'
    || normalized === 'localhost.'
    || normalized === '[::1]'
    || normalized === '::1') {
    return true;
  }

  // A DNS name that merely begins with "127." is not loopback. Require an
  // actual IPv4 literal before permitting cleartext relay traffic.
  return net.isIP(normalized) === 4 && normalized.split('.')[0] === '127';
}

function normalizeRelayUrl(value) {
  const parsed = parseUrl(value, { defaultToHttps: true });

  if (parsed.protocol === HTTP_PROTOCOL && !isLoopbackHostname(parsed.hostname)) {
    throw new TypeError('HTTP is allowed only for localhost or a loopback IP address.');
  }

  if (parsed.hostname.toLowerCase() === 'localhost.') {
    parsed.hostname = 'localhost';
  }

  return parsed.origin;
}

function isAllowedNavigation(candidate, relayUrl) {
  let parsedCandidate;
  let normalizedRelay;

  try {
    parsedCandidate = parseUrl(candidate);
    normalizedRelay = normalizeRelayUrl(relayUrl);
  } catch {
    return false;
  }

  return parsedCandidate.origin === normalizedRelay;
}

function normalizeExternalUrl(value) {
  try {
    return parseUrl(value).href;
  } catch {
    return null;
  }
}

function isAllowedWebPermission(permission, candidate, relayUrl) {
  return ALLOWED_WEB_PERMISSIONS.has(permission)
    && isAllowedNavigation(candidate, relayUrl);
}

module.exports = {
  isAllowedNavigation,
  isAllowedWebPermission,
  isLoopbackHostname,
  normalizeExternalUrl,
  normalizeRelayUrl,
};
