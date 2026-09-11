# Plan

Outstanding work on the sync project, split by how soon it has to happen.

| Document | Meaning | Items |
| --- | --- | --- |
| [now.md](now.md) | ~~Nothing syncs until these land.~~ **All done.** Kept for the reasoning. | 5 |
| [next.md](next.md) | Sync works, but these are wrong, unverified, or missing before anyone relies on it. Only item 1 is open, and it needs a Mac. | 5 |
| [later.md](later.md) | Worth doing once it works, plus the things deliberately **not** done and why. | 9 |

## Where things stand

Built and tested (`:core:allTests :app:shared:jvmTest :server:test`, all passing):

- **Core sync primitives** — `core/src/commonMain/kotlin/lux/uno/anima/sync/`. Hybrid
  logical clock, the record shape, `merge()`, the wire protocol.
- **Record metadata** — `FinanceData.meta` carries an `Hlc` and a tombstone flag per record,
  stamped automatically on every mutation.
- **Record mapping** — `FinanceData` converts to and from `SyncRecord`, price histories
  included. Two simulated devices converge through the real server in `SyncRoundTripTest`.
- **Sync state** — a stable per-install node id, the cursor and the push mark, persisted per
  environment.
- **Client and engine** — `KtorSyncApi` over HTTP, and `SyncEngine` driving push-and-pull to
  convergence, with offline and signed-out handled without touching local data.
- **Wired up** — the app syncs on launch and after each edit. Verified end to end: the real
  desktop app pulling from the real server.
- **Accounts** — sign in, register and sign out in the app, with a sync status that tells
  offline from failed. Driven on a real phone against a real server.
- **Icons** — content-addressed by SHA-256 and synced as blobs, with a one-time migration
  off the old random ids.
- **Credential storage** — `SecretStore` in `core/…/platform/`, with Keychain, Android
  Keystore and permission-restricted-file implementations. The desktop file's permissions
  are asserted against the disk, not assumed.
- **Reminder bookkeeping** — which reminders this device has delivered lives in its own
  local file, out of the document that syncs.
- **Server** — `server/src/main/kotlin/`. SQLite, Argon2id accounts, revocable sessions,
  `/sync`, `/blobs`.

**Sync works, end to end, from the app.** What is left is in [next.md](next.md): the iOS
code now compiles — which caught three casts that could never succeed, so keychain storage
there was entirely dead — but nothing on iOS has ever *run*, and that needs a Mac. There is
also no connectivity monitoring, so a failed sync waits for the next edit or launch.

Architectural rules that constrain all of this live in [../AGENTS.md](../AGENTS.md).
Read it before starting anything here — particularly the privacy rules, which rule out
several otherwise obvious shortcuts.
