#!/bin/sh
# Compatibility entrypoint.  The maintained installer lives in diorama-parent.
set -eu

parent=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -x "$parent/install.sh" ]; then
    exec "$parent/install.sh" --components anatomist "$@"
fi

echo "anatomist/install.sh moved to the diorama-parent release repository." >&2
echo "Use: curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | sh -s -- --components anatomist" >&2
exit 2
