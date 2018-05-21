Build the image:
```
docker build -t mirror .
```

Start a *mirror server* on the current machine:
```
sudo docker run --rm --init -it -eU=$(id -u) -eG=$(id -g) -v $(pwd):/data --net=host mirror ./mirror server
```

Start a *mirror server* on a remote machine with SSH:
```
USER=$(whoami)
HOST=<remote-hostname>

ssh -L 49172:localhost:49172 -l "${USER}" "${HOST}" -t '\
  echo '999999' | sudo tee /proc/sys/fs/inotify/{max_user_watches,max_queued_events,max_user_instances} >/dev/null && \
  cd $(mktemp -dt) && \
  sudo docker run --rm --init -it -eU=$(id -u) -eG=$(id -g) -v $(pwd):/data --net=host mirror ./mirror server
'
```

Start a *mirror client* connecting to the SSH tunnel and sync the current directory:
```
docker run --rm --init -it -eU=$(id -u) -eG=$(id -g) -v $(pwd):/data --net=host mirror ./mirror client \
  --local-root /data \
  --remote-root /data \
  --host localhost
```
