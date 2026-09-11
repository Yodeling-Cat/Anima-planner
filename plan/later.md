# Later — once it works

Nothing here blocks anything. The second half records decisions **not** to do things, so
they do not get re-litigated from scratch.

---

## Housekeeping the server will eventually need

### Tombstone collection

Deleted records stay in `records` forever today. Drop tombstones older than **90 days**.

That number is not arbitrary and must not be lowered casually: a device that has been
offline longer than the retention window will resurrect every record it deleted while
away, because the tombstone that would have out-voted its stale copy is gone. 90 days is
the longest plausible gap between a phone and a server.

### Orphaned blobs

An icon whose subscription is deleted leaves its bytes on disk forever. Sweep for blobs
no live record references.

Do it slowly and conservatively — a blob deleted while a device still holds the only
reference to it is a permanently broken image. Consider a grace period rather than an
immediate sweep.

### Backups

One SQLite file plus a blob directory is the entire server state. `VACUUM INTO` gives a
consistent snapshot without stopping the server. Nothing automates this yet.

---

## Deployment

The server currently binds `0.0.0.0:8080` with no TLS. Before it faces the internet:

- TLS, almost certainly by putting it behind a reverse proxy rather than terminating in
  Ktor.
- Keep `ANIMA_REGISTRATION_OPEN` unset once the accounts that should exist do.
- `ANIMA_DATA_DIR` on a volume that survives a redeploy.

The rate limiter keys on `call.request.local.remoteAddress`. **Behind a proxy that will
be the proxy**, making one client's failed logins throttle everyone. Switch it to a
forwarded-header source at the same time as adding the proxy, not after.

---

## Session management

The `sessions` table supports listing and revoking, and nothing exposes it. Sessions last
a year, so a lost laptop currently has no remedy short of a database edit. This is the
control the desktop credential-storage decision leans on — see the Credentials section of
[../AGENTS.md](../AGENTS.md) — so it matters more than its size suggests.

---

## Dependency versions to confirm

Added without verifying they are current: `sqlite-jdbc 3.47.1.0`, `bcprov-jdk18on 1.80`.
Both resolve and pass tests. Check for newer releases, particularly Bouncy Castle, which
is a security dependency.

---

# Deliberately not done

Each of these was considered during the design and rejected for a reason. Revisit them on
purpose, not by accident.

### End-to-end encrypting the payload

The architecture is already shaped for it — the typed spine carries everything the server
acts on, the payload is an opaque string, and the column is a `BLOB`. Dropping ciphertext
in changes no server code.

What stops it is key management, not storage. A key that syncs between devices needs
either a user passphrase separate from the login password, or a device-pairing flow. Both
are real features, and getting either wrong locks the user out of their own data with no
recovery. Not worth it until the rest is solid.

### Hoisting domain foreign keys into columns

`categoryId` and `paymentMethodId` stay inside the payload. The server never joins on
them, never validates them, and the client already tolerates dangling ids by design.
Promoting them would only teach the server which subscriptions share a category.

### Price points as records of their own

`priceHistory` is a growing list inside a last-write-wins record, which is why it needs the
union in `FinanceRecords.applying`. Giving each `PricePoint` its own record — kind
`pricePoint`, id `<subscriptionId>:<effectiveFrom>` — would drop the special case entirely:
each point becomes an independent record that an edit to the subscription's name cannot
clobber, and removing one becomes an ordinary tombstone.

The union converges, so this is not urgent. What it would fix is the window it leaves: a
device that records a price and loses the merge holds that point alone until its *second*
sync, and a device wiped in between takes the point with it. One push would be enough with
per-point records.

The cost is a migration of every existing subscription, and a new record kind.

### Field-level last-write-wins

Would narrow conflicts further — editing the name on one device and the price on another
would keep both. Rejected because the single exception that actually loses data
(`priceHistory`) is handled directly, and field-level merging costs per-field clocks on
every record for a case a single user rarely hits.

### A real OS keyring on desktop

macOS `security`, Linux `secret-tool`, Windows DPAPI. Each needs either a subprocess or a
native dependency, on a project that otherwise ships pure Kotlin everywhere. The
permission-restricted file matches what `gh`, `aws` and `docker` do, and revocable
sessions are the control that actually limits the damage.

### `CLAUDE.md` as a real symlink — DONE

It is a symlink now, recorded by git as mode `120000` whose whole content is the string
`AGENTS.md`. It was a hardlink before, which git cannot represent at all: both names were
committed as separate blobs, so any clone got two independent files free to drift apart
with no warning.

Two things that cost time, worth not rediscovering:

- **`New-Item -ItemType SymbolicLink` fails even with Developer Mode on.** Windows
  PowerShell 5.1 does not pass `SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE`, so it asks
  for elevation regardless. `cmd /c mklink CLAUDE.md AGENTS.md` does pass it and works.
- `git config core.symlinks true` has to be set on **every** clone — it is local config, not
  something the repository carries. Without it a checkout writes CLAUDE.md as a plain text
  file containing the word `AGENTS.md`, which is worse than the hardlink it replaced.
