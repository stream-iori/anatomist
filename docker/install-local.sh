#!/usr/bin/env bash
# Install the native anatomist binary into ~/.local/bin so it's on PATH
# next to other user-local tools (agy, graphify, ...). Idempotent.
#
# Usage:
#   ./docker/install-local.sh                # install target/anatomist
#   ./docker/install-local.sh path/to/bin    # install a specific binary
#
# The previous install (if any) is backed up to anatomist.bak.
set -euo pipefail

DEFAULT_DEST="${HOME}/.local/bin"
DEST="${ANATOMIST_INSTALL_DIR:-$DEFAULT_DEST}"

cd "$(dirname "$0")/.."
ROOT="$PWD"

SRC="${1:-$ROOT/target/anatomist}"
if [[ ! -x "$SRC" ]]; then
    echo "ERROR: $SRC does not exist or is not executable." >&2
    echo "Run \`mvn -Pnative -DskipTests package\` (macOS/linux native build)" >&2
    echo "or \`./docker/build-linux-amd64.sh\` (cross-build for Linux amd64) first." >&2
    exit 1
fi

mkdir -p "$DEST"

# Refuse to install a binary built for a different OS/arch silently.
HOST_OS="$(uname -s)"
HOST_ARCH="$(uname -m)"
case "$(file -b "$SRC" 2>/dev/null)" in
    *"Mach-O"*"arm64"*)   BIN_TARGET="macOS arm64" ;;
    *"Mach-O"*"x86_64"*)  BIN_TARGET="macOS x86_64" ;;
    *"ELF 64-bit"*"x86-64"*) BIN_TARGET="Linux x86_64" ;;
    *"ELF 64-bit"*"aarch64"*) BIN_TARGET="Linux aarch64" ;;
    *) BIN_TARGET="unknown" ;;
esac
echo "Source binary  : $SRC ($BIN_TARGET)"
echo "Install target : $DEST/anatomist  (host=$HOST_OS $HOST_ARCH)"

case "$HOST_OS/$HOST_ARCH/$BIN_TARGET" in
    "Darwin/arm64/macOS arm64"|\
    "Darwin/x86_64/macOS x86_64"|\
    "Linux/x86_64/Linux x86_64"|\
    "Linux/aarch64/Linux aarch64")
        ;;
    *)
        echo "WARN: source binary architecture does not match host. Continuing." >&2
        ;;
esac

# Back up an existing install rather than blindly overwriting.
if [[ -e "$DEST/anatomist" ]]; then
    cp -p "$DEST/anatomist" "$DEST/anatomist.bak"
    echo "Backed up previous install -> $DEST/anatomist.bak"
fi

install -m 0755 "$SRC" "$DEST/anatomist"

# macOS: re-stamp adhoc signature so Gatekeeper accepts the binary
# in its new location. amfid caches verdicts per inode; a fresh
# install resets the cache.
if [[ "$HOST_OS" == "Darwin" ]]; then
    xattr -cr "$DEST/anatomist" 2>/dev/null || true
    codesign --force --sign - "$DEST/anatomist" 2>/dev/null || true
fi

echo
echo "Installed. Verify with:"
echo "  $DEST/anatomist --version"
echo
if [[ ":$PATH:" != *":$DEST:"* ]]; then
    echo "NOTE: $DEST is not on \$PATH. Add this to your shell rc:"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
fi
