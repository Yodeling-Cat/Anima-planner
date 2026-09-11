# Anima — working rules

Kotlin Multiplatform. `core` holds domain logic shared by every target, `app/shared`
holds Compose UI, `server` is a Ktor service. Android, iOS and Desktop all run the
same `core`.

## Two ways to be hosted

Anima must stay deployable both ways, and neither is the real one:

1. **Self-hosted** — someone runs `server/deploy/` for themselves or their household.
2. **Hosted by us** — one deployment serving strangers, each with their own account.

A feature that only makes sense for one has to degrade cleanly in the other rather than
being assumed away, and anything needing configuration gets a default that works for a
self-hoster who will never read the docs. Nothing may require an account with us.

This is what the data boundary below is protecting. Because the server holds opaque
payloads it cannot read, the difference between the two is who runs the process — not how
much that process can see. A feature that needs the server to understand a payload breaks
self-hosting's promise and our hosted offer's at the same time.

Two settings, and they answer different questions. **`ANIMA_JOIN_PASSWORD`** decides *who
may create an account*: whoever can type it. Absent, nobody new can join. **`ANIMA_PUBLIC_INSTANCE`**
decides *what this deployment is* — a service strangers subscribe to, which is what brings
erasure, export, per-user limits and a named controller with it. Neither implies the other:
a public instance may keep a join password through a soft launch, and a private one with a
password is still just a household.

Both are set once with `bin/deploy --password` / `--public` and persist on the host. They
are deliberately not per-deploy: a setting that has to be turned off again by a second
deploy stays on for exactly as long as somebody forgets.

See `plan/later.md` for what is still missing before serving strangers is responsible —
deletion, export, per-user quotas, password reset.

## The data boundary

Anima is **offline-first**. The local dataset is the source of truth the UI reads
from; the server is a replica that devices converge on. Every feature must work with
the network off, including writes.

### What the server is allowed to know

> The server owns the semantics of everything it must enforce, query, or authorize
> on, and nothing else.

In practice that means a **typed spine with opaque leaves**:

- **Typed columns** for identity, ownership, conflict ordering, soft-delete state,
  the kind discriminator, and anything that appears in a `WHERE` clause or a
  permission check.
- **Opaque payload** for everything else. The server stores it, versions it, hands
  it back, and never parses it.

A column earns its place by being *used* by the server. Domain foreign keys
(`categoryId`, `paymentMethodId`) are not hoisted out of the payload: the server
never joins on them, never validates them, and the client already tolerates dangling
ids. Hoisting them would only teach the server which subscriptions share a category.

The payload crosses the wire as a **string**, not as structured JSON. Typing it as a
`JsonElement` would oblige the server to parse it just to hand it back, and a server
that parses is one careless commit away from a server that reads. As a string it can
become ciphertext without the protocol changing at all. Do not add server code that
depends on reading it.

### Privacy

Treat this as a privacy-focused app. Hold only what the job requires:

1. **Collect the minimum.** Email plus a password hash is the whole user record.
   No analytics, no device fingerprints, no telemetry, no access logs carrying
   payload contents.
2. **Compute on the device.** Totals, renewal dates, overviews, charts, reminders
   and search run in `core` on the client. The server has no domain endpoints —
   `/sync` and `/blobs` move bytes and nothing else. If a feature seems to need
   server-side computation, that is a design smell; solve it locally first.
3. **Never log payloads or blob contents.** Log record ids, kinds and counts.
4. **New endpoints are a decision, not a detail.** Any route that reads inside a
   payload needs an explicit reason recorded here.

## Sync model

Per-record last-write-wins. Single user, so the newest write is always the one the
user meant — merges never prompt.

- **Hybrid logical clock** per record (`physicalMillis:counter:nodeId`, encoded so
  lexicographic order matches causal order). Clock skew between devices cannot make
  an older edit win. Every device advances its clock past any HLC it receives.
- **Tombstones**, never hard deletes. Delete-versus-edit resolves by recency like
  any other conflict. Tombstones are collected after 90 days, which must stay longer
  than the longest plausible offline window.
- **Server-assigned `seq`** is the sync cursor — a monotonic per-user counter, not a
  timestamp. Clients store "seen up to N".
- `POST /sync` pushes and pulls in one round trip, and echoes back the merged result
  of the push so a client learns when its write lost.
- **Blobs are content-addressed** by SHA-256, of the bytes as stored rather than as
  uploaded. Identical bytes produce identical ids, so binary data is immutable, never needs
  conflict resolution, and a cached image can never be stale. Blobs move only after records
  have settled, and only ones a live record still points at.

The one exception to whole-record replacement is `Subscription.priceHistory`: it is
merged as a union keyed by `effectiveFrom`, because record-level LWW would otherwise
drop a price point recorded on the other device.

Device-local state (handled reminder ids, sync cursor, node id) lives in a local prefs
file and is never synced.

## Environments

Three, in `core/…/dev/Environment.kt`: **Production**, **Development**, **On device only**.
Switched from the developer screen — shake the device for a second, or Ctrl+Shift+D on
desktop — and applied without a restart, because the data layer in `App.kt` is `remember`ed
against the environment id and rebuilt when it changes.

**Environments never share storage.** Every file and directory name carries the
environment's `storageSuffix`, and `EnvironmentStoreTest` asserts no two overlap. Adding
anything new that persists means giving it a suffixed name too.

Production's suffix is empty, so the files an existing install already wrote stay its files.
Do not "tidy" this into `-prod` — it would move real user data on upgrade to save a filename.

The sandbox has no `serverUrl` and cannot be given one by configuration. Its promise is that
what goes in never leaves the device, and a setting that could revoke that is not a setting.

Anything not Production shows a persistent banner. The mistake it prevents is entering real
data into a test environment and finding out a week later.

## Credentials

`SecretStore` holds the session token and the email address it belongs to, kept apart from
the dataset — never `FinanceStore`. Nothing else belongs in there.

The address is there rather than in the plain device preferences because it identifies a
person, which earns it the OS keystore on a phone and an owner-only file on a desktop. It
deliberately **outlives a sign-out**: a token can go at any time, and asking which address
this device uses every time one does is friction for nothing. Only "remove data" clears it.

| Platform | Backing | `SecretProtection` |
| --- | --- | --- |
| iOS | Keychain, `AfterFirstUnlock` so background sync can read it while locked | `OS_KEYSTORE` |
| Android | AES/GCM under an Android Keystore key; only ciphertext in preferences | `OS_KEYSTORE` |
| Desktop | File readable by this user account only — `0600`, or a single-entry ACL on Windows | `FILE_PERMISSIONS` |

Desktop is plaintext at rest on purpose. A desktop app holds no secret an attacker
already running as the user could not also read, so encrypting the file with a key
stored beside it would buy the appearance of safety and nothing else. The control that
actually limits the damage is server-side: sessions are revocable. Do not "fix" this
with a hardcoded key.

Every read may return null — a restored backup invalidates Android's key material, and
a Keychain entry does not survive the app being deleted. That means signed out. It
never means the local dataset is touched.

A failed or rejected sync must never discard local data. A 401 leaves the app fully
usable and the pending queue intact; re-authentication happens on reconnect.

## Navigation

`Navigator` in `app/shared/…/ui/nav/` is a plain back stack. Because it is the app's own,
it has to claim the platform's back affordance — Android otherwise finishes the activity,
so a swipe from a detail screen quits instead of going up a level.

There is one `goBack` in `App.kt`, and Escape, the Android gesture and the Android button
all call it. Do not add a second path: two implementations are what let them drift apart
in the first place. `SystemBackHandler` is disabled at the root of the stack on purpose —
the last back press belongs to Android, and swallowing it traps the user in the app.

## Surviving recreation

Android destroys and rebuilds the activity on every configuration change — rotation, dark
mode, font size, locale. `remember` does not survive that; `rememberSaveable` does.

**Anything the user typed or chose belongs in `rememberSaveable`.** Form fields, open
dialogs, the sort order, the back stack. `remember` is for things that can be recomputed:
derived values, objects rebuilt from persisted data.

Types saved state cannot hold directly get a `Saver` in `ui/components/Savers.kt`. Every
`restore` there returns null instead of throwing when it meets something it cannot read —
saved state outlives the code that wrote it, and a field back at its default beats a crash
on rotate.

Dialogs hold the **id** of the record they act on, never a copy. A copy goes stale the
moment the record changes underneath, and an id is something saved state can keep.

## Conventions

- Comments explain *why*, not *what* — see the existing files in
  `core/src/commonMain` for the register to match.
- Domain models are `@Serializable` data classes with defaults, so adding a field is
  a backward-compatible read.
- `FinanceStore` reads and writes the whole dataset as one JSON document. The volumes
  here do not justify a database on the client.
- Tests live beside the code they cover in `commonTest` and must not need a network.
- **No commas in backtick test names.** Kotlin/Native rejects them, so one comma breaks
  `:core:allTests` on every target at once while `jvmTest` stays green. Rephrase instead.
- The server module's own code lives under `lux.uno.anima.server.*`; `lux.uno.anima.sync`
  belongs to `core` and is shared with it. Do not split a package across the two.

## Commands

- Desktop: `./gradlew :app:desktopApp:run` (hot reload: `:app:desktopApp:hotRun --auto`)
- Android: `./gradlew :app:androidApp:assembleDebug`
- Server: `./gradlew :server:run`
- Tests: `./gradlew :core:allTests :app:shared:jvmTest :server:test`
- Deploy the server: `bin/deploy` — see `server/deploy/README.md`

`allTests` compiles the iOS targets on Windows but cannot run them — Gradle disables
`iosSimulatorArm64Test` on a non-Mac host. Compiling them still catches real errors, so do
not narrow the command to skip them.
