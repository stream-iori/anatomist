#!/bin/sh
set -e

REPO_BASE="${ANATOMIST_MIRROR:-http://6.12.3.250:8100/dist-bin}"
INSTALL_DIR="${ANATOMIST_INSTALL_DIR:-$HOME/.local/bin}"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "${OS}-${ARCH}" in
    darwin-arm64)  BINARY="anatomist-darwin-aarch64" ;;
    # linux-x86_64)  BINARY="anatomist-linux-amd64" ;;
    *)
        echo "Error: unsupported platform ${OS}-${ARCH}"
        echo "Currently only darwin-arm64 (Apple Silicon) is supported."
        exit 1
        ;;
esac

URL="${REPO_BASE}/${BINARY}"

echo "Installing anatomist..."
echo "  Platform: ${OS}-${ARCH}"
echo "  From:     ${URL}"
echo "  To:       ${INSTALL_DIR}/anatomist"

# Create install dir
mkdir -p "${INSTALL_DIR}"

# Download
if command -v curl >/dev/null 2>&1; then
    curl -fSL --progress-bar "${URL}" -o "${INSTALL_DIR}/anatomist"
elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress "${URL}" -O "${INSTALL_DIR}/anatomist"
else
    echo "Error: curl or wget required"
    exit 1
fi

chmod +x "${INSTALL_DIR}/anatomist"

# Verify
if "${INSTALL_DIR}/anatomist" --version >/dev/null 2>&1; then
    echo "  Installed: $(${INSTALL_DIR}/anatomist --version)"
else
    echo "  Installed successfully."
fi

# Check PATH
case ":${PATH}:" in
    *":${INSTALL_DIR}:"*) ;;
    *)
        echo ""
        echo "NOTE: ${INSTALL_DIR} is not in your PATH."
        echo "Add this to your ~/.zshrc:"
        echo ""
        echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
        echo ""
        ;;
esac
