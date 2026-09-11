# Deploying the Anima server

One SQLite file and a blob directory are the entire server state, so this is a small
deployment: a single container, one named volume, and a route on the proxy that is
already running on the host.

The host also runs Kamal-managed apps. **kamal-proxy owns ports 80 and 443**, so Anima
publishes no host port at all — it joins the `kamal` Docker network and the proxy reaches
it by container name. That is also where TLS comes from; nothing here terminates it.

## Deploying

```
bin/deploy
```

From PowerShell, `bin\deploy.ps1` instead — it finds Git Bash and runs the same script.

That is the whole thing, first time and every time: it builds the fat jar, copies it and
these two files to `~/anima` on the host, rebuilds the image, waits for the container to
report healthy, points kamal-proxy at it, and checks `https://anima.lux.uno/health` from
outside. Safe to run again at any point — a failed step stops before anything further
changes.

Two flags:

```
bin/deploy --public              # this boot accepts sign-ups from anyone
bin/deploy --skip-build          # ship the jar already in build/libs
```

Defaults sit at the top of `bin/deploy` and each reads an environment variable, so a
different host or domain needs no edit: `ANIMA_SSH`, `ANIMA_REMOTE_DIR`, `ANIMA_DOMAIN`.

The remote directory holds only the jar, the Dockerfile and the compose file. Everything
that matters is in the `anima-data` volume, which no deploy touches.

## Why the jar is built outside the image

`./gradlew :server:buildFatJar` runs on the developer machine and the resulting
`server-all.jar` is copied in. Building it inside the image would mean installing the
Android SDK there, because Gradle configures every module in the build and
`:app:androidApp` needs it to configure at all. The jar is plain JVM bytecode and
`sqlite-jdbc` carries natives for every platform it supports, so a jar built on Windows
runs unchanged on the host.

`server/build.gradle.kts` pins `jvmToolchain(21)` to match the JRE in the Dockerfile.
Without the pin the target follows whichever JDK runs Gradle, and the first developer on
a newer one would ship a jar the host cannot load.

## Letting someone join

A private server admits whoever can type its **join password**. Set it once:

```
bin/deploy --password 'a memorable passphrase'
```

It is written to `.env` on the host with mode 600 and travels there over stdin, so it
reaches neither this machine's shell history nor the server's process list. It persists:
every later `bin/deploy` leaves it alone, so letting one more person in costs nothing.

They enter it in the app under **developer screen → Server → Join password**, beneath the
address, then create their account. It is not an account password and is never needed
again after joining — signing in afterwards uses their own credentials.

The join password is **optional and strongly recommended**. Without one a private server
admits nobody at all, which is safe but means no one can ever join; the alternative of
admitting anyone who finds the address is not offered, because a URL is not a secret.
Remove it with `bin/deploy --password ''` when you are done inviting people.

Keep it to letters, digits and dashes: it lands in a `.env` file, where a `#` would begin
a comment.

The app tells the two refusals apart by the `code` in the error body, never by its prose:

| | `code` | The app says |
| --- | --- | --- |
| No join password set | `registration_closed` | Not accepting new accounts; ask for a join password |
| Wrong one offered | `join_password_wrong` | That join password is wrong |

Both are refused inside the auth rate limiter — ten attempts a minute per address — which
is what makes a hand-typed secret defensible.

## Public instances

```
bin/deploy --public
```

This does **not** open registration; the join password decides that. What it declares is
that this deployment serves strangers, which brings obligations Anima cannot yet meet —
account deletion, data export, per-user storage limits. Do not set it on a real service
until those land. `--private` undoes it, and private is the default.

## What the script needs from the host

Checked in one round trip before anything is copied, and reported by name if missing:

- the login user can reach Docker without sudo,
- the `kamal` network exists,
- a `kamal-proxy` container is running.

The proxy's own `deploy --help` is read to find out whether that version takes
`--health-check-path`, because the flag name has moved between releases.

The route is re-registered on every deploy rather than only the first. kamal-proxy treats
it as the same routing swap it performs for its own apps, and it means a route lost to a
proxy rebuild comes back without anyone remembering a separate command.

## The base image, and Docker Hub

The Dockerfile pulls its JRE from `public.ecr.aws/docker/library/eclipse-temurin`, AWS's
mirror of the Docker Official Image, rather than from Docker Hub.

The reason is that a Hub pull carries whatever credential the host has stored for Hub,
even for a public image that needs no account. This host runs Kamal, Kamal stores a Hub
credential, and a stale one makes the build fail with `401 Unauthorized` on an image
anybody can pull anonymously. Sourcing the base elsewhere keeps this deploy independent
of that credential's state.

`JRE_IMAGE` overrides it if Hub is ever preferable:

```
JRE_IMAGE=eclipse-temurin:21-jre-noble bin/deploy
```

## Forwarded headers

`ANIMA_TRUST_FORWARDED_HEADERS=true` is set in `compose.yaml`, and the login rate limiter
keys on the forwarded address rather than the socket. Behind the proxy every request
arrives from the same socket address, so without it one person's failed logins would
throttle everyone.

It is only safe while the container cannot be reached except through the proxy. If Anima
is ever given a published host port, that setting has to go back to false in the same
change — a client that can reach the server directly could otherwise name its own address
on every attempt and never hit the limit.

## Backups

The server takes its own, nightly, in process. Nothing to schedule and no sidecar to keep
alive — a self-hoster who never reads this file still ends up with verified copies.

Each run writes to the `anima-data` volume under `/data/backups`:

- `anima-<date>.db` — a `VACUUM INTO` copy, which is transactionally consistent even
  though writers never stopped, then opened and checked with `PRAGMA integrity_check`
  before it is allowed to replace the day's file. A copy nobody has read is not a backup.
- `blobs-<date>/` — hard links to the blob tree. Blobs are content-addressed and written
  once, so a link can never come to mean different bytes, and a daily snapshot costs an
  inode each.

Settings, all optional:

| Variable | Default | |
| --- | --- | --- |
| `ANIMA_BACKUP_ENABLED` | `true` | |
| `ANIMA_BACKUP_RETENTION_DAYS` | `14` | Also how long a deleted account's data lingers |
| `ANIMA_BACKUP_INTERVAL_HOURS` | `24` | |

### This is not disaster recovery

Your host already snapshots the volume, and that covers losing the disk better than this
ever will. These copies are for the other kind of accident — a bad deploy, a bug that
deletes records, someone asking for yesterday back — where rolling the whole volume back
would also roll back every other app sharing it.

That division is why writing them to the same volume is right rather than lazy: losing the
volume is already covered. It also means the host's snapshots now contain point-in-time
database copies as a bonus.

### Restoring one day

```
ssh user@65.108.255.16
cd anima
docker compose stop
docker run --rm -v anima-data:/data alpine sh -c '
  cp /data/backups/anima-2026-09-11.db /data/anima.db &&
  rm -f /data/anima.db-wal /data/anima.db-shm &&
  cp -rn /data/backups/blobs-2026-09-11/. /data/blobs/'
docker compose start
```

**Deleting `anima.db-wal` and `anima.db-shm` is not optional.** The database runs in WAL
mode, so a write-ahead log belonging to the database you just replaced would be replayed
over the top of the restored one on the next open, quietly undoing the restore.

Blobs are copied with `-n` rather than replacing the directory: they are immutable and
additive, so restoring only needs to put back the ones that went missing.

Check before starting, from the host:

```
docker run --rm -v anima-data:/data alpine ls -la /data/backups
```
