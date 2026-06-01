# Linux amd64 build artifacts

Native binaries produced by `docker/build-linux-amd64.sh`. The directory
is git-ignored — rebuild on demand.

| File | Built from | Runs on |
|---|---|---|
| `anatomist-linux-amd64` | `docker/Dockerfile.amd64-build` | Linux x86_64 with glibc ≥ 2.17 (CentOS 7+, RHEL 7+, Ubuntu 16.04+, Debian 9+) |

## Reproducible build

```bash
./docker/build-linux-amd64.sh
# → target/anatomist (inside the container, then host-side after volume mount)
# → dist/anatomist-linux-amd64 (after manual cp from target/)
```

## Smoke (Linux host)

```bash
./anatomist-linux-amd64 index /path/to/your/project \
    --project-source /path/to/your/project/src/main/java \
    --output /tmp/your.db
./anatomist-linux-amd64 search YourClass --index /tmp/your.db
```

## Cross-platform notes

The binary is built with `--initialize-at-build-time` for anatomist /
javaparser / ASM. It includes sqlite-jdbc's bundled native libsqlite for
linux-x86_64 and dlopens it at startup; no separate `libsqlite3` is
needed on the host.
