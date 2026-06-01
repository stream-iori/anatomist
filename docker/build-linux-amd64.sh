#!/usr/bin/env bash
# Build the Linux amd64 native binary for anatomist using the CentOS-7-based
# builder image. Mounts the host workspace into the container, runs
# mvn -Pnative package inside, then leaves the artifact at
#   target/anatomist  (Mach-O on macOS host? No — this is Linux ELF amd64;
#                       valid on glibc >= 2.17 hosts)
#
# Usage:
#   ./docker/build-linux-amd64.sh
#   ./docker/build-linux-amd64.sh --rebuild-image    # force docker build
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

IMAGE_TAG="anatomist-build:linux-amd64"
DOCKERFILE="docker/Dockerfile.amd64-build"

REBUILD=0
for arg in "$@"; do
    case "$arg" in
        --rebuild-image) REBUILD=1 ;;
    esac
done

if [[ $REBUILD -eq 1 ]] || ! docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
    echo "==> Building build image $IMAGE_TAG"
    docker build --platform linux/amd64 -t "$IMAGE_TAG" -f "$DOCKERFILE" .
else
    echo "==> Using existing build image $IMAGE_TAG (pass --rebuild-image to force)"
fi

echo "==> Running mvn -Pnative -DskipTests package inside the builder"
# Maven cache: use a project-local cache so we don't fight with host ~/.m2 paths
# (host settings.xml may pin a macOS-only <localRepository>). The cache survives
# across runs at .m2-cache/.
mkdir -p "$ROOT/.m2-cache"
docker run --rm --platform linux/amd64 \
    -v "$ROOT:/workspace" \
    -v "$ROOT/.m2-cache:/home/anatomist/.m2" \
    -w /workspace \
    "$IMAGE_TAG" \
    bash -c "mvn -Pnative -DskipTests package && file target/anatomist && ls -la target/anatomist"

echo
echo "==> Artifact:"
file "$ROOT/target/anatomist" || true
ls -la "$ROOT/target/anatomist" || true
echo
echo "Done. Distribute target/anatomist to any Linux amd64 host with glibc >= 2.17."
