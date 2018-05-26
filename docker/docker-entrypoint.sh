#!/bin/bash
set -e

# Environment variables `$U` and `$G` can be used to control what user (UID)
# and group (GID) the `mirror` process will be started with.
# Default is the current user inside the container, which is "root".
U=${U:-$(id -u)}
G=${G:-$(id -g)}

# Create a user with the given UID if it doesn't exist yet
if ! (getent passwd "${U}" >/dev/null); then
  useradd -u "${U}" "user${U}"
fi

# Create a group with the given GID if it doesn't exist yet
if ! (getent group "${G}" >/dev/null); then
  groupadd -g "${G}" "group${G}"
fi

exec gosu "${U}:${G}" /opt/mirror/mirror "$@"
