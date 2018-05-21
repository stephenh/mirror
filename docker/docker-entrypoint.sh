#!/bin/bash
set -e

if ! (getent passwd "${U}" >/dev/null); then
  useradd -u "${U}" "user${U}"
fi

if ! (getent group "${G}" >/dev/null); then
  groupadd -g "${G}" "group${G}"
fi

exec gosu "${U}:${G}" "$@"
