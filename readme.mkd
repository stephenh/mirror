
[![Build Status](https://travis-ci.org/stephenh/mirror.svg?branch=master)](https://travis-ci.org/stephenh/mirror)
[![Docker Repository on Quay](https://quay.io/repository/stephenh/mirror/status "Docker Repository on Quay")](https://quay.io/repository/stephenh/mirror)

Primary Workflow
================

Mirror is built to support a two-machine (e.g. desktop+laptop) development workflow where you want to run a command line compile/build process on a powerful/dedicated desktop, but still edit files remotely on a laptop.

This is fairly common (see "Comparison to Existing Options" section below), but what makes Mirror unique is that it is two-way: it simultaneously syncs both laptop-to-desktop as well as desktop-to-laptop, in real time.

For my personal use case, this is to facilitate using an IDE on the laptop (e.g. for code completion, navigation, etc.), and IDEs often need local access to the binary artifacts (or build time-generated source code) from the desktop-hosted build process, e.g.:

* On your laptop, save `projectA/foo.java`
  * Mirror sends `foo.java` to the desktop
* On your desktop, the build system picks up the `projectA/foo.java` change and creates `projectA-snapshot.jar`
  * Mirror sends `projectA-snapshot.jar` back to the laptop
* On your laptop, the IDE can now use `projectA-snapshot.jar` for code completion/etc. when editing `projectB/bar.java`

Granted, the IDE will also do local compilation of `projectA/foo.java`, so ideally the IDE could use in-workspace references, where the IDE-compiled `projectA/foo.java` is already/immediately on the IDE classpath for `projectB`. If you can setup your projects this way (e.g. with [m2e](http://www.eclipse.org/m2e/) or [IvyDE](https://ant.apache.org/ivy/ivyde/)), that is generally preferable.

However, for larger/more complex projects, e.g. those with various pre-/post-compilation code generation steps (that are often only performed in the CLI build), the IDE just can't reproduce the build process closely enough to fully compile `projectA` on it's own, and so using CLI-provided `projectX-snapshot.jar` artifacts is the only way to do local cross-project imports.

This scenario (local edits + remotely-built cross-project artifacts) is what Mirror addresses.

Goals
=====

* Real-time, two-way sync between a desktop development machine, and an editor-/IDE-only laptop
* Native file system events (e.g. inotify) fired when files are changed (otherwise IDEs require explicit refreshes/polling)

Non-Goals
=========

* Unison-style/long-duration disconnected support
  * `mirror` will automatically re-connect (e.g. if you close your laptop and then go home) and restart syncing when it detects the server is available again (inspired by mosh), but if files have changed on both sides while disconnected, then the last write wins
  * This hueristic is generally fine, it just means `mirror` is not meant for a use case of "make new changes on the desktop for a few days, make new changes on the laptop for a few days, and then run `mirror` once per week to intelligently merge your work". Use `git` or `unison` for that; `mirror` is for real-time syncing.
* Maintain Unix permissions/owner/group
  * Whatever Unix user runs the `mirror` commands will be the owner/group/etc. of the files
* Support for huge files
  * The assumption is that most files are source code, and occassional binary artifacts that are generally in the below-100mb range
* Super-efficient diff/transmission logic like rsync
  * Instead we assume a generally fast network connection (as in "faster than a modem", i.e. mirror works fine over a VPN)
  * Basically, if a file changes, `mirror` retransmits the whole file instead of trying to diff only what changed

Comparison to Existing Options
==============================

I looked at several sync options before starting mirror, but didn't find anything that quite fit:

* X-Forwarding (to just run everything, IDE included, on the desktop) has noticeable lag, even on a LAN
* rsync is not two-way, nor real-time
* unison is two-way, but not real-time
* lsyncd is real-time, but does not officially support two-way (see [issue 303](https://github.com/axkibe/lsyncd/issues/303#issuecomment-244569307))
* sshfs is too slow and doesn't support inotify
* NFS and other network file systems don't support inotify
* [SyncThing](https://syncthing.net/) is a dropbox alternative if you're looking for a backup solution
* doppleganger (an internal tool) is real-time, but not two-way
  * It also generally assumes you can run the build tool (e.g. gradle) on both laptop and desktop, and for my setup I want to only run it on the desktop
  * Similarly, doppleganger does let you have a git working copy on both laptop/desktop, but so far mirror generally assumes you have a git copy on only one of the desktop or laptop (your choice) and then the other side is just a dumb copy. Which means git commands, like `git log`, `tig`, etc., will only work on whatever machine you have your git check on.

Install
=======

To use the latest release/pre-built jars:

* Install Java 8
  * You can try running `java -version` to see if Java is already installed
  * If not, a you'll need a platform-specific installation step, like `sudo apt install openjdk-8-jre` on Ubuntu
* Install [watchman](https://facebook.github.io/watchman/)
* Download the latest [mirror](https://github.com/stephenh/mirror/releases/latest/download/mirror) and [mirror-all.jar](http://github.com/stephenh/mirror/releases/latest/download/mirror-all.jar) to your home directory (or some other directory on your path, e.g. `~/bin`)
  * `wget https://github.com/stephenh/mirror/releases/latest/download/mirror-all.jar ~/`
  * `wget https://github.com/stephenh/mirror/releases/latest/download/mirror ~/`
* Make the `mirror` file executable
  * `chmod u+x mirror`
* Copy both to your remote home directory (or some other directory on your path, e.g. `~/bin`)
  * `scp mirror-all.jar your-desktop.com:~/`
  * `scp mirror your-desktop.com:~/`
* Start the server-side from the desktop's home directory
  * `./mirror server`
* Start the client-side from the laptop's home directory
  * `./mirror client -h your-desktop.com -l ./code/ -r ./code/`
  * *Note:* Be careful using the tilda (e.g. `~/code`), as your shell will resolve that, e.g. to `/Users/you/code`, and that resolved path on the client might be not valid on the server

This will sync the `$HOME/code` directory on your two machines.

(Note: for Arch Linux users, AUR has the [`mirror-sync`](https://aur.archlinux.org/packages/mirror-sync) and [`mirror-sync-git`](https://aur.archlinux.org/packages/mirror-sync-git) packages.)

Config
======

By default, `mirror` will not sync any files in your `.gitignore` files.

However, you can also configure `mirror` with extra includes or excludes in addition to the `.gitignore`, e.g. if for some reason you want to not sync files that are not ignored, or sync files that are actually ignored (e.g. certain build artifacts).

Extra includes and excludes patterns can be passed when starting the client, and follow the `.gitignore` format, e.g.:

    ./mirror client --include '*-SNAPSHOT.jar` --include '.classpath' --include '.project' --exclude `build/`

If you'd like mirror to completely ignore your `.gitignore` files, i.e. to sync everything, you can use `--include '*'`.

(There is also a `--use-internal-patterns` that has useful defaults if you work at the same place I do.)

Help
====

Options for both the `client` and `server` commands are available by running:

    `./mirror help client`

Or:

    `./mirror help server`

For example, the client supports `--debug-all` and `--debug-prefixes` command line parameters to output debug info for all or subsets of the paths (setting these parameters on the client session will pass them to the client's server session, so that the server will use those options for its own session with that specific client).

Git Usage Note
==============

(**Update February 2020**: Lately I have been ignoring this advice and passing `-i .git` to have both sides sync their `.git` metadata so that staging/index/etc. status is all shared, and it's been working out really well so far.)

In general, `mirror` will work best if you have just one machine (either the desktop or laptop) have the git (or svn/other SCM) working copy (or working copies if you're syncing a directory like `~/code` with multiple repositories), and have the non-git machine just get all of it's files via `mirror` from git-using machine, and not by also having it's own `git` working copy.

The reason is that if you had `git` working copies on both the laptop and desktop, you could run into:

1. Desktop: `git checkout master`
2. Laptop: `git checkout master`
3. Run `mirror`, everything syncs fine, because they're on the same branch
4. On the laptop, run `git checkout feature_a`
5. `mirror` copies all the files changes on the laptop (basically all `feature_a`'s changes) to the server
6. Now the server git directory *thinks* it's still on `master`, but we've written the `feature_a` files to it

Basically, `mirror` makes no attempts to keep your two `git` working copies on the same branch, and instead just naively copies files back/forth.

So, instead, it works better if you only ever run `git` commands on the desktop, and so any branch changes happen there, and then the laptop just follows/copies where ever the server is at.

The downside of this approach is that you can't use `git log`, `git blame`, etc. on the laptop. But the upshot is that's pretty simple, and makes it harder/less likely to accidentally nuke code by either knowingly or unknowingly getting the two machines on different branches, and then having one overwrite the other.

System Watch Limits
===================

Note that if you have a lot of directories, you might have to increase the native file system limits, e.g.

* For Linux see [this readme](https://github.com/guard/listen/blob/master/README.md#increasing-the-amount-of-inotify-watchers)
  * `echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf && sudo sysctl -p`
  * `echo fs.inotify.max_queued_events=50000 | sudo tee -a /etc/sysctl.conf && sudo sysctl -p`
* For Mac, see [the watchman docs](https://facebook.github.io/watchman/docs/install.html#system-specific-preparation)

Watchman Config
===============

If you have an extremely large number of files that you don't want to sync, `.gitignore` may not be enough, because mirror and the underlying Watchman tool will still initially load those files into memory, even to decide "oh right, don't sync them".

To have Mirror and watchman fundamentally ignore things, you can create a `.watchmanconfig` file with the `ignore_dirs` property set.

See the [watchman config](https://facebook.github.io/watchman/docs/config.html).

Syncing More than Two Machines
==============================

Although not an initial design goal, due to it's approach, `mirror` also supports a hub and spoke model of syncing more than two machines.

E.g. you could have:

1. Desktop runs the mirror server process (you don't need to start multiple `mirror server` processes)
2. Laptop 1 connects to the desktop and syncs it's `~/code` to the desktop's `~/code`
3. Laptop 2 also connects to the desktop and syncs it's `~/code` to the desktop's `~/code`

Now all three machines will be kept in sync.

Syncing Jar Caches
==================

If you're running most build commands on your desktop, but the IDE on your laptop, your `.classpath`/etc. files will likely have references to downloaded jars, e.g. in the Maven cache or Gradle cache directories.

Since these files are cached and so don't change, and aren't created very often (only when dependencies are updated), I currently sync these jar caches as needed with a shell script:

```bash
rsync -azP user@your-desktop.com:.ivy2/sbt/ ~/.ivy2/sbt/
rsync -azP user@your-desktop.com:.gradle/caches/ ~/.gradle/caches/
```

In theory mirror could keep these in sync as well, either by just running ~2-3 more invocations of the `mirror` client, but it would also be nice to pass in multiple sync directories in a single `mirror` command invocation, e.g.:

```bash
mirror \
  --sync ./.ivy2/sbt:./.ivy2/sbt \
  --sync ./.gradle/caches:./.gradle/caches \
  --sync ./code:./code
```

But currently mirror only supports a single remote/local sync at a time.

Secure Communication
====================

mirror currently uses plain text for its communication protocol, as the primary use case is syncing a desktop/laptop that are on an assumed-secure internal network or VPN connection.

The underlying RPC framework, GRPC, supports TLS communication, but mirror currently does not leverage that.

If you need secure communication, e.g. are syncing across the open Internet with sensitive data, you can use SSH tunneling, e.g.:

* On your client, run `ssh -L 49172:localhost:49172 your-remote-host`
* On your remote host, start the server as usual, `./mirror server`
* On your client, run mirror with `./mirror client -h localhost ...`

This will have the mirror client send traffic to the `localhost:49172` port, which SSH will securely tunnel to your remote host.

Running with Docker
===================

A docker image is available at [quay.io/stephenh/mirror](https://quay.io/stephenh/mirror).

Since the container will have its own filesystem separate from the host's filesystem,
usually you'll want to mount some directory *into* the container to make it available for
synchronisation.
To mount the current working directory as `/data` into the container, pass `-v $(pwd):/data` to docker.

By default docker runs processes as `root`, which results in all written files owned by `root` on the host system.
To run the process as your current user, pass `-u $(id -u):$(id -g)` to docker.

To start a **_mirror_ server** available on port 49172, with the current working directory mounted at `/data` run:
```bash
docker run --rm --init -it -u $(id -u):$(id -g) -v $(pwd):/data -p 49172:49172 \
  quay.io/stephenh/mirror server
```

To start a **_mirror_ client** with the current working directory mounted at `/data` (and syncing the local `/data` with the remote `/data`) run:
```bash
docker run --rm --init -it -u $(id -u):$(id -g) -v $(pwd):/data \
  quay.io/stephenh/mirror client \
  --local-root /data \
  --remote-root /data \
  --host <SERVER-HOST>
```

Compiling/Contributing
======================

If you want to hack on mirror locally, you should be able to:

* Clone this repository
* Run `./gradlew shadowJar`
  * This will download mirror's dependencies and produce an all-in-one jar in `build/lib/mirror-all.jar`
* Run `./mirror ...` (e.g. either `mirror client` or `mirror server`
  * The `mirror` script in the base directory should pick up your locally-built `build/lib/mirror-all.jar`

If you want to use your locally-built jar on your remote host, you'll need to `scp` the new `mirror-all.jar` to your remote host (in the same directory as the `mirror` script, which will then use your new `mirror-all.jar` instead of the previously-downloaded version.)

Todo
====

* Configuration via a `.mirrorrc` file (if necessary)
* Really easy setup/install process, e.g.:
  * Currently you have to run `mirror ...` and `mirror ...` on both sides
  * Ideally you could just run `mirror` on the client, and it would self-start the desktop-side process by SSH'ing the jar over
  * Maybe the server-side should be an always-running daemon? Same thing with the client-side?
* Really easy upgrade process
  * Ping `repo.joist.ws/mirror-version.txt`, and if a new version, exit with a code that tells bootscript script to redownload the jar
* Include file hashes to avoid sending files with different modification times
  * See Digest for an experiment; collecting digests does slow down the initial scan, but not egregiously
  * Currently needless syncs don't seem that frequent/expensive (text files are small), so this is low priority
* Use file hashes to detect renames
  * Right now renaming `~/code/project-a` to `~/code/project-b` will treat every file as a delete+create
  * So far seems like a limited use case, and also is still handled pretty quickly, so this is also low priority
* Support `svn:ignore`
  * Would be easiest by converting it in-memory to `.gitignore`-format via something like [this](https://gist.github.com/iegik/3450385)
  * Or else just suggest users use git-svn for all svn repos
* Support `.git/info/exclude`?
  * Not sure how often this is used


