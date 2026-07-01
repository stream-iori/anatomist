#!/bin/sh
set -e

REPO_BASE="${ANATOMIST_MIRROR:-http://6.12.3.250:8100/dist-bin}"
INSTALL_DIR="${ANATOMIST_INSTALL_DIR:-$HOME/.local/bin}"
SKILL_URL="${REPO_BASE}/anatomist/SKILL.md"
SKILL_INSTALL="${ANATOMIST_INSTALL_SKILL:-1}"
SKILL_CLIENTS="${ANATOMIST_SKILL_CLIENTS:-qoder codex claude}"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "${OS}-${ARCH}" in
    darwin-arm64)  BINARY="anatomist-darwin-aarch64" ;;
    linux-x86_64)  BINARY="anatomist-linux-amd64" ;;
    *)
        echo "Error: unsupported platform ${OS}-${ARCH}"
        echo "Supported platforms: darwin-arm64, linux-x86_64."
        exit 1
        ;;
esac

URL="${REPO_BASE}/${BINARY}"

download_to() {
    url="$1"
    output="$2"

    if command -v curl >/dev/null 2>&1; then
        curl -fSL --progress-bar "${url}" -o "${output}"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress "${url}" -O "${output}"
    else
        echo "Error: curl or wget required"
        exit 1
    fi
}

install_skill_for_client() {
    client="$1"

    case "${client}" in
        qoder)
            skill_dir="${HOME}/.qoder/skills/anatomist"
            ;;
        codex)
            skill_dir="${CODEX_HOME:-${HOME}/.codex}/skills/anatomist"
            ;;
        claude)
            skill_dir="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}/skills/anatomist"
            ;;
        "")
            return 0
            ;;
        *)
            echo "  Skip unknown skill client: ${client}"
            return 0
            ;;
    esac

    mkdir -p "${skill_dir}"
    download_to "${SKILL_URL}" "${skill_dir}/SKILL.md"
    echo "  ${client}: ${skill_dir}/SKILL.md"
}

echo "Installing anatomist..."
echo "  Platform: ${OS}-${ARCH}"
echo "  From:     ${URL}"
echo "  To:       ${INSTALL_DIR}/anatomist"

# Create install dir
mkdir -p "${INSTALL_DIR}"

download_to "${URL}" "${INSTALL_DIR}/anatomist"

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

case "${SKILL_INSTALL}" in
    0|false|FALSE|no|NO)
        echo "Skip skill install."
        ;;
    *)
        echo ""
        echo "Installing anatomist skill..."
        echo "  From: ${SKILL_URL}"
        for client in ${SKILL_CLIENTS}; do
            install_skill_for_client "${client}"
        done
        ;;
esac
