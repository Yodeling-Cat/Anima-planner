# Next — before anyone relies on it

Sync works at this point. These are the things that are wrong, unverified, or missing.

---

## 1. iOS — compiled now, still never run

**Correction to what this file used to say:** the iOS targets are *not* disabled on Windows.
`./gradlew :core:compileKotlinIosSimulatorArm64` cross-compiles here fine. What cannot happen
on Windows is *running* the tests — Gradle disables `iosSimulatorArm64Test` with "cannot run
on the current host". So the code was never compiled only because nobody asked it to be.

Asking it to be found real bugs.

### Fixed

`SecretStore.ios.kt` used `as` casts between Kotlin `String` and `NSString` in three places.
They compile and **never succeed at runtime**, so every keychain read returned null and every
write built a null key. iOS credential storage did not work at all. It now goes through
`NSString.create` and `NSData` conversions, and the target compiles with no warnings.

`NSString.create(data:encoding:)` also needs `import platform.Foundation.create` — Kotlin/Native
exposes Objective-C factory methods as companion extensions, and without the import the name
simply does not resolve.

### Still unverified

Compiling is not running. Nothing here has executed on a device, and these are the claims the
comments make:

- The token survives an app restart.
- It is readable while the phone is locked but **after** the first unlock since boot — that is
  what `kSecAttrAccessibleAfterFirstUnlock` buys, and background sync depends on it.
- Deleting and reinstalling the app clears it.

`ShakeDetector.ios.kt` (CoreMotion) and the Ktor Darwin engine are in the same position:
they compile, and that is all that is known.

Needs a Mac. Nothing else here does.

---

## 2. Windows ACL on the credentials file — DONE

It works. `icacls` was not the way to find out — the file only exists once something has
signed in, and a one-off check would not catch it breaking later.

`FileSecretStore` now takes its directory, and `FileSecretStoreTest` (jvmTest, 5 cases)
asserts against what actually reached the disk: on Windows the ACL must be exactly one ALLOW
entry for the owner, on POSIX the mode must be `0600`. A filesystem offering neither view
fails the test rather than passing quietly — `restrict()` is wrapped in `runCatching`, so a
silent failure is otherwise indistinguishable from success, which is the whole hazard.

`SecretProtection.FILE_PERMISSIONS` is now a checked claim rather than an intention.

---

## 3. Icons to content-addressing — DONE

`IconStore` names every file after the SHA-256 of its own bytes — of the **resized** bytes,
so the id always describes exactly what is on disk and what the server will verify. The same
picture added on two devices reaches the same id, so binary data is out of the conflict story
entirely: a blob is present or absent, never newer.

`put` refuses bytes that do not hash to the name they were given. An id that stopped
describing its contents would poison every device that later asked the server for it.

### Migration

`migrateToContentAddressing()` renames anything still carrying a random id and reports what
moved; `FinanceRepository.applyIconMigration` rewrites the records pointing at them, stamped
like any other edit because the record genuinely changed. Idempotent — a file already named
after its own hash is skipped, so an interrupted run simply carries on.

Verified on a real phone: a planted `icon-legacyid.png` and a subscription referencing it
both came out the other side as `5735…f6`.

### Blob sync

`BlobSync` runs **after** records settle, and only over blobs a live record still points at —
which icons are worth moving is decided by the records, so moving them earlier would work
from a list about to change. An orphan is left for the pruning pass rather than handed to the
server.

A blob referenced but not yet on the server is not an error: the device holding it has not
uploaded it, and the next sync finds it.

Covered by `IconStoreTest` (10) and three round trips through the real server.

---

## 4. `handledReminderIds` out of the synced data — DONE

**The old note here overstated the problem.** `handledReminderIds` was never in any record
payload — `toRecords` covers subscriptions, payment methods, categories and settings, and
nothing else — so it never actually synced and never caused a conflict. What it really did:

- every delivered notification called `mutate`, which rewrote the whole `finance.json` and
  bumped `localEdits`, scheduling a debounced sync with nothing to send;
- it put device state in the document that syncs, one careless commit away from being real.

Now `HandledReminderStore` — an interface, `InMemoryHandledReminderStore` for tests, and
`JsonFileHandledReminderStore` writing `handled-reminders$storageSuffix.json` beside the
sync state. `ReminderCoordinator` takes one; `FinanceData.handledReminderIds` and
`FinanceRepository.setHandledReminders` are gone.

**No migration.** Dropping the field means the ids already on disk are forgotten once, so a
reminder that was handled may fire one more time after the upgrade. That is strictly smaller
than the duplicate-across-devices cost already accepted below, and it avoids handing the
repository a second store to orchestrate on load.

**Decision unchanged:** a reminder dismissed on the phone *will* appear again on the desktop.
Mildly repetitive beats a document that rewrites itself every time a notification fires.

Covered by `HandledReminderStoreTest` (5) and three cases in `ReminderCoordinatorTest`,
including one asserting that a delivery leaves `repository.data` byte-identical.

---

## 5. Sign-in UI and sync status — DONE

`Account` in `core/…/sync/` owns the session; `AccountScreen` is reached from a person icon
on the dashboard, which also carries the only sync signal there — one dot.

**The email address is stored**, in `SecretStore` beside the token — it identifies a person,
so it earns the same protection rather than the plain prefs file. It outlives a sign-out on
purpose: a token can go at any time, and asking which address this device uses every time one
does is friction for nothing. Signing back in asks only for a password. It is written only
after the server accepts, so a typo never becomes the address this device offers back for
good, and only "Remove data" clears it.

**Sign out asks, and the safe answer is the button.** "Sign out, keep data" is the confirm;
"Remove data" sits with Cancel and is never the default. When anything is still waiting to go
up, the dialog says how much and in the error colour — that is the one moment where removing
local data is unrecoverable.

Wiping also writes a **fresh node id**. After a wipe this is a new device as far as the
server is concerned, and the old push mark would describe records that are no longer here.

### Telling the states apart

`AccountState.headline()` distinguishes *syncing*, *failed*, *offline*, *n waiting to go up*
and *up to date*. Offline with nothing pending earns no dashboard dot at all: that is the
normal state of a phone, and marking it would teach people to ignore the dot.

The pending count is derived with `recordsToPush(nodeId, lastPushed)` rather than tracked, so
it cannot disagree with the data it describes.

A `403` from a closed server is `AccountResult.RegistrationClosed`, which says what to do
about it rather than showing a status code.

### Verified on a real phone, against a real server

Created an account from the app, added sample data, and watched the debounced sync push all
19 records. Then signed out keeping data: the token left secure storage and all seven
subscriptions stayed. Covered by `AccountTest` (9).
