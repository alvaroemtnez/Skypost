#!/bin/sh
# Run a command in the simulation container.
#   WORK   host work directory (default /tmp/skypost-sim), mounted at /work
#   PODMAN podman command (inside the VSCodium flatpak: "flatpak-spawn --host podman")
# The XARA sources are read from $WORK/xara-copy (a copy, so that the :z
# SELinux relabel never touches your own clone).
WORK=${WORK:-/tmp/skypost-sim}
exec ${PODMAN:-podman} run --rm -e JOBS="${JOBS:-15}" \
  -v "$WORK":/work:z -v "$WORK/xara-copy":/src/xara:ro,z \
  localhost/skypost-modemsim "$@"
