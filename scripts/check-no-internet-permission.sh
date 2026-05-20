#!/usr/bin/env bash
# Privacy gate. Fails the build if any merged AndroidManifest contains
# android.permission.INTERNET (or related networking permissions) as an
# active <uses-permission> declaration.
#
# Run after `./gradlew :app:processReleaseManifest :app:processDebugManifest`.
#
# Implementation note: we use python's XML parser instead of grep because
# the source manifest contains the string "INTERNET" inside a warning
# comment, and we want a structural check that ignores comments and
# correctly handles the tools:node="remove" override pattern.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST_DIR="$ROOT/app/build/intermediates/merged_manifests"

if [ ! -d "$MANIFEST_DIR" ]; then
  echo "::error::Merged manifests not found at $MANIFEST_DIR. Run :app:processDebugManifest / :app:processReleaseManifest first."
  exit 1
fi

python3 - "$MANIFEST_DIR" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"

FORBIDDEN = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}

manifest_dir = Path(sys.argv[1])
failed = False

for manifest in manifest_dir.rglob("AndroidManifest.xml"):
    try:
        tree = ET.parse(manifest)
    except ET.ParseError as e:
        print(f"::error file={manifest}::Could not parse manifest: {e}")
        failed = True
        continue

    root = tree.getroot()
    for perm in root.iter("uses-permission"):
        name = perm.get(f"{ANDROID}name")
        node = perm.get(f"{TOOLS}node")
        # tools:node="remove" actively REMOVES a transitive permission — that's how we
        # explicitly strip INTERNET if some library tries to reintroduce it.
        if node == "remove":
            continue
        if name in FORBIDDEN:
            print(
                f"::error file={manifest}::Forbidden permission '{name}' "
                f"detected in {manifest.relative_to(manifest_dir)}"
            )
            failed = True

    # Also catch transitive components that could schedule background network I/O
    # (notably the Google datatransport JobScheduler/AlarmManager workers from MediaPipe).
    # See docs/decisions/0007-strip-transitive-network-perms.md.
    FORBIDDEN_COMPONENT_PREFIXES = (
        "com.google.android.datatransport.",
    )
    for tag in ("service", "receiver", "provider"):
        for comp in root.iter(tag):
            name = comp.get(f"{ANDROID}name") or ""
            node = comp.get(f"{TOOLS}node")
            if node == "remove":
                continue
            if any(name.startswith(p) for p in FORBIDDEN_COMPONENT_PREFIXES):
                print(
                    f"::error file={manifest}::Forbidden background component "
                    f"'{name}' in {manifest.relative_to(manifest_dir)}"
                )
                failed = True

if failed:
    print()
    print("Privacy pillar 1 (no network) is non-negotiable — see docs/decisions/0002-privacy-enforcement.md.")
    sys.exit(1)

print("Privacy gate OK — no forbidden network permissions in merged manifests.")
PY
