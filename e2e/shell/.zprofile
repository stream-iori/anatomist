if [[ -n "${ANATOMIST_E2E_BIN:-}" ]]; then
  export PATH="${ANATOMIST_E2E_BIN:h}:${PATH}"
fi
