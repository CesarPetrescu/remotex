# Remotex for Windows

The Windows app is a small Electron shell around the web client served by a
Remotex relay. It does not bundle a second UI or talk to Codex directly, so it
has the same session features as the web app and updates when the relay's web
bundle is updated.

## Install and connect

Download one of the Windows x64 assets from
[GitHub Releases](https://github.com/CesarPetrescu/remotex/releases):

- `remotex-windows-<version>-setup.exe` — per-user NSIS installer with an
  optional install-directory picker.
- `remotex-windows-<version>-portable.exe` — runs without installation.

On first launch, enter the base URL that serves your Remotex web app, such as
`https://relay.example.com`. Public relays must use HTTPS. Plain HTTP is
accepted only for `localhost` and loopback IPs. The selected origin is stored
in Electron's user-data directory; use **Relay -> Change Relay**
(`Ctrl+,`) to replace it later.

The desktop setup stores no bearer token. Sign in with the relay's user token
inside the loaded web app and choose the web client's session-only or
persistent storage option there. Public builds contain neither an operator's
relay URL nor the repository's loopback-only demo credentials.

## Security boundary

The relay page runs with Node integration disabled, context isolation and the
Chromium sandbox enabled, and no preload bridge. Navigation is restricted to
the exact selected relay origin; other HTTP(S) links open in the system
browser. Webviews, device permissions, mixed content, drag-and-drop
navigation, and production DevTools are disabled. The only page permissions
allowed are sanitized clipboard writes and turn-completion notifications,
both restricted to the exact selected relay origin.

The local first-run page is a separate, non-persistent partition with a
Content Security Policy and a narrow preload API that can only read or replace
the relay URL. IPC calls also verify that they came from that setup window.

This boundary protects the desktop shell, but the selected relay still serves
the application code and receives the bearer token, prompts, and session
events. Connect only to a relay operator you trust.

## Develop and test

Node.js 22 or newer is the supported local toolchain:

```bash
cd apps/desktop
npm ci
npm run lint
npm test
npm audit
```

Run the unpacked development app with:

```bash
npx electron .
```

Build the Windows x64 NSIS and portable executables with:

```bash
npm run pack:win -- --publish never
```

The canonical package build runs on `windows-latest` in both CI and the
release workflow. `electron-builder` writes local outputs under `dist/`, which
is gitignored.

## Signing and releases

Windows artifacts are not Authenticode-signed yet, so SmartScreen can show an
unknown-publisher warning. Verify the matching entry in `SHA256SUMS.txt` and
the GitHub build-provenance attestation before running a downloaded binary.
Stable semver tags and the rolling `nightly` release are published by
`.github/workflows/release.yml`.
