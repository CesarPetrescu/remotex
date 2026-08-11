#!/usr/bin/env bash
set -euo pipefail

# GitHub's macos-15 images occasionally have an iOS runtime and device type
# installed without a pre-created simulator. Resolve a stable device UUID for
# xcodebuild, creating one when the runner image did not provide any.
devices="$(xcrun simctl list devices available)"
device_id="$(
  sed -nE 's/^[[:space:]]*iPhone 16 Pro \(([0-9A-Fa-f-]{36})\).*/\1/p' <<<"$devices" |
    sed -n '1p'
)"

if [[ -z "$device_id" ]]; then
  device_id="$(
    sed -nE 's/^[[:space:]]*iPhone .* \(([0-9A-Fa-f-]{36})\).*/\1/p' <<<"$devices" |
      sed -n '1p'
  )"
fi

if [[ -z "$device_id" ]]; then
  runtime_id="$(
    xcrun simctl list runtimes available |
      sed -nE 's/^iOS .* - (com\.apple\.CoreSimulator\.SimRuntime\.iOS-[^[:space:]]+)$/\1/p' |
      tail -n 1
  )"
  device_type_id="$(
    xcrun simctl list devicetypes |
      sed -nE 's/^iPhone 16 Pro \((com\.apple\.CoreSimulator\.SimDeviceType\.[^)]+)\)$/\1/p' |
      sed -n '1p'
  )"
  if [[ -z "$device_type_id" ]]; then
    device_type_id="$(
      xcrun simctl list devicetypes |
        sed -nE 's/^iPhone .* \((com\.apple\.CoreSimulator\.SimDeviceType\.[^)]+)\)$/\1/p' |
        sed -n '1p'
    )"
  fi
  if [[ -z "$runtime_id" || -z "$device_type_id" ]]; then
    echo "No available iOS simulator runtime/device type was found." >&2
    xcrun simctl list runtimes >&2
    xcrun simctl list devicetypes >&2
    exit 1
  fi
  device_id="$(xcrun simctl create 'Remotex CI iPhone' "$device_type_id" "$runtime_id")"
  echo "Created iOS simulator $device_id." >&2
else
  echo "Using existing iOS simulator $device_id." >&2
fi

if [[ ! "$device_id" =~ ^[0-9A-Fa-f-]{36}$ ]]; then
  echo "simctl returned an invalid device identifier: $device_id" >&2
  exit 1
fi

printf '%s\n' "$device_id"
