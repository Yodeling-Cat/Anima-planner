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

### Backups — DONE

`server/…/backup/Backups.kt`, in process, nightly, on by default. `VACUUM INTO` through a
connection of its own — not the shared one, because `VACUUM` cannot run inside a
transaction and borrowing it would hold the write mutex for the length of the copy — then
`PRAGMA integrity_check` on the result before it is allowed to become the day's backup.
Blobs are captured as hard links, which their content addressing makes safe and nearly
free. Retention defaults to 14 days.

In process rather than a sidecar because of the self-hosting half of "Hosting other
people" below: a precaution that has to be set up separately is one a self-hoster will not
have. A failure is logged and swallowed — a server that refuses to serve because it could
not copy itself has turned the precaution into the outage.

Deliberately **not** disaster recovery, and not a second copy of what the host's volume
snapshots already do. Those cover losing the disk. These cover restoring one app to one
moment, which a volume rollback cannot do without taking everything else on the box with
it. Same volume is therefore the right place for them.

Still open:

- **Off-box copies.** Everything above shares a failure domain with the thing it protects.
  The host's snapshots are the second domain today, which is adequate but is somebody
  else's promise rather than ours.
- **A restore that has been rehearsed.** The procedure is written down in
  `server/deploy/README.md` and has never been run against a real incident. The WAL
  footgun in it — a stale `-wal` replaying over a restored database — is exactly the kind
  of thing a rehearsal finds and a document does not.

---

## Deployment

`bin/deploy` is the whole procedure — build, copy, rebuild, wait for healthy, register
the route, verify over TLS — and is safe to re-run. `server/deploy/` holds the Dockerfile,
the compose file and the runbook behind it. The server runs as a container with no
published host port, joined to the `kamal` network the host's other apps already use,
with kamal-proxy terminating TLS for `anima.lux.uno`.

Settled:

- TLS at the proxy rather than in Ktor, as expected.
- `ANIMA_DATA_DIR` on a named volume that a redeploy does not touch.
- The rate limiter keys on `call.request.origin.remoteAddress`, with `XForwardedHeaders`
  installed only when `ANIMA_TRUST_FORWARDED_HEADERS` says a proxy really is in front.
  Trusting the header on a directly reachable port would hand every client a way around
  the limit, so that flag and a published port must never both be on.

Still open:

- Admission is a join password (`ANIMA_JOIN_PASSWORD`), set once with
  `bin/deploy --password` and kept in a mode-600 `.env` on the host. Absent means nobody
  new can join. `ANIMA_PUBLIC_INSTANCE` is a separate question — what this deployment *is*,
  not who may register — and is what the obligations below hang off.
- Nothing collects tombstones or orphaned blobs. Backups are handled — see above.

---

## Hosting other people

Anima is meant to be deployable two ways, and both have to stay first class:

1. **Self-hosted** — someone runs `server/deploy/` on their own box for themselves or
   their household. This is what works today.
2. **Hosted by us** — one deployment serving strangers who sign up, each with their own
   account.

Neither is the "real" mode. A feature that only makes sense for one of them has to degrade
cleanly in the other rather than being assumed away, and anything below that needs
configuration gets a sensible default for a self-hoster who will never read the docs.

The data boundary already carries this: the server stores opaque payloads it cannot read,
so the difference between hosting yourself and being hosted is who runs the process, not
how much they can see. Keep it that way — a feature that needs the server to understand a
payload would break self-hosting's main promise and our hosted offer's at the same time.

What isolation already does hold: every server query is scoped `WHERE user_id = ?`, blobs
live under `<dataDir>/blobs/<userId>/`, and identical bytes are deliberately **not**
deduplicated across accounts, because sharing storage would tell the server which users
hold the same file. Multi-tenancy is not the gap. The gaps are below.

These are what `--public` promises and cannot yet keep. Until they land, setting
`ANIMA_PUBLIC_INSTANCE` on a deployment that actually serves strangers is writing a cheque
the code does not cover.

### Account deletion and data export

Neither exists. Deleting a user today means editing SQLite by hand and removing a blob
directory, and there is no way at all to hand someone their data back.

Both are required to serve users in the EU — erasure and portability — and both are worth
having regardless, because "I want my data out" is a reasonable thing to ask of any app
and answering it badly is how a product earns distrust.

- **Deletion** must take the user row, their sessions, every record, and every blob
  directory under their id. It must be the user's own action from the app, not a support
  request. Because records are tombstoned rather than dropped everywhere else, this is the
  one place a hard delete is correct — a tombstone that outlives the account it belonged to
  is the opposite of erasure.
- **Export** should hand back the payloads as they are stored, plus the blobs. The server
  cannot render them into anything friendlier without learning to read them, so the
  readable export belongs in `core` on the client, where the data is already plaintext.

### Per-user limits

There are none. `MAX_BLOB_BYTES` caps a single blob at 5 MB and `MAX_CHANGES_PER_REQUEST`
caps one request, but nothing caps what an account accumulates over time. One user can
upload blobs until the disk is full, and the disk filling takes down everyone else's sync
along with their own.

Needed before opening registration to strangers:

- a **total storage quota per user**, counting blob bytes and payload bytes together,
- a **record count ceiling** per user,
- a clear over-quota response the client can explain, rather than a failed sync that looks
  like a bug.

Self-hosters should get a generous default and a way to raise it; the limit exists to stop
one account harming others, which is a problem a household deployment does not have.

### Password reset by email

Wanted, and it is the thing that will hurt first once strangers sign up: there is no reset
flow, no email verification, and no mail sending anywhere in the server. A user who forgets
their password is locked out of sync permanently, with nothing support can do.

This needs a deliberate decision recorded here, because it cuts against the privacy rules
in [../AGENTS.md](../AGENTS.md):

- The address is already stored — it is half the login — so reset does not collect
  anything new. What is new is **sending** to it, which means an outbound mail path and a
  third party that learns who has an account here.
- Reset tokens must be single-use, short-lived, and stored hashed, the way session tokens
  already are.
- A reset must **revoke existing sessions**, or a leaked mailbox silently keeps the old
  attacker signed in.
- The response to "reset my password" must not reveal whether the address has an account,
  matching what `signIn` already does.
- Self-hosted deployments will have no SMTP credentials and must stay fully usable without
  them. With mail unconfigured the endpoint should refuse cleanly and the app should say so,
  rather than the feature appearing to work.

A reset mail is the first thing this server would ever send. That is a boundary worth
crossing on purpose.

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

### `CLAUDE.md` beside `AGENTS.md` — DONE, and not with a link

`CLAUDE.md` is a plain one-line file containing `@AGENTS.md`. That is a Claude Code import:
the rules stay in `AGENTS.md` alone, and there is no second copy to drift. Same shape as
`lux-uno/conflux`.

Two link-based approaches were tried first and are worse:

- **A hardlink** — what this was. Git cannot represent one, so both names were committed as
  separate blobs and every clone got two independent files free to drift with no warning.
- **A symlink** — git records it properly as mode `120000`, but a checkout only recreates it
  when `core.symlinks` is true, and that is **local config every clone has to set for
  itself**. Without it the checkout writes a text file containing the word `AGENTS.md`,
  which is the hardlink problem again with an extra step. Worth knowing anyway:
  `New-Item -ItemType SymbolicLink` demands elevation even with Developer Mode on, because
  Windows PowerShell 5.1 does not pass `SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE`;
  `cmd /c mklink` does.

The import needs nothing configured, on any platform. Tools that read `AGENTS.md` — the
cross-editor convention — get the real thing either way.
