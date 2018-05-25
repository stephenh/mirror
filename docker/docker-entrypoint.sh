#!/bin/bash
set -e

U=${U:-$(id -u)}
G=${G:-$(id -g)}

if ! (getent passwd "${U}" >/dev/null); then
  useradd -u "${U}" "user${U}"
fi

if ! (getent group "${G}" >/dev/null); then
  groupadd -g "${G}" "group${G}"
fi

exec gosu "${U}:${G}" /opt/mirror/mirror "$@"
