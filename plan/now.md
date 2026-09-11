# Now — nothing syncs until these land

Strictly ordered. Each one builds on the one above it.

**All five are done.** Sync runs; what is missing is a way to sign in — see `next.md`.

---

## 1. Record metadata on `FinanceData` — DONE

`FinanceData.meta: Map<String, RecordMeta>`, keyed `"kind:id"`. A live record sits in its
typed list *and* in `meta`; a tombstone sits only in `meta` with `deleted = true`. The UI
still reads `data.subscriptions` and never sees a tombstone, so no screen changed.

Two things worth knowing before building on it:

- **Stamping is worked out by comparison, not declared.** `FinanceData.restamped(previous,
  clock)` diffs before against after inside `FinanceRepository.mutate()`. No mutator can
  forget to stamp, and second-order changes get caught — deleting a payment method rewrites
  every subscription that pointed at it, and those really have changed.
- **The clock recovers itself on load.** `load()` calls `clock.observe()` on every reading
  in the file before issuing any of its own, so the highest stamp on disk acts as the last
  reading this device gave out. That removes most of what step 3 was going to be for: a
  wall clock that jumped backwards while the app was closed cannot reissue a reading.

Schema version is 4. Covered by `RecordMetaTest` (14 tests).

**Still open from this step:** the node id is generated per `FinanceRepository` and not
written down, so it changes each launch. It only breaks `Hlc` ties, so nothing is wrong
today — but step 3 should persist it.

## 2. `FinanceData` ↔ `SyncRecord` mapping — DONE

`core/…/finance/sync/FinanceRecords.kt`:

```kotlin
fun FinanceData.toRecords(): List<SyncRecord>
fun FinanceData.recordsToPush(nodeId: String, after: Hlc = Hlc.Zero): List<SyncRecord>
fun FinanceData.applying(incoming: List<SyncRecord>, clock: HybridClock): FinanceData
```

`toRecords` is driven by `meta`, not the typed lists — a tombstone exists only there, and a
delete that never reached the server would be undone by the next pull.

**Pending is derived, not tracked.** `recordsToPush` selects records whose `Hlc` carries this
device's node id. A record that arrived from elsewhere keeps that device's id and is not sent
straight back; one that arrived and was then altered here carries this device's, so it is.
That removes the separate pending set the plan originally called for, and with it the risk of
it drifting out of step with the data.

`applying` advances the clock past every reading it is shown, win or lose. That lives inside
`applying` rather than in the engine because applying a record without acknowledging its
clock is never correct.

Covered by `FinanceRecordsTest` (19) and `SyncRoundTripTest` (6) — the latter drives two
simulated devices through the **real server** over the real protocol.

### What the round-trip test showed about `priceHistory`

The union works, but **convergence can take more than one exchange**, and that is inherent:

1. Both devices edit the same subscription offline; the desktop's edit is newer.
2. The phone pushes first and the server takes it.
3. The desktop pushes and **wins**, overwriting the phone's payload on the server before the
   desktop has ever seen it. The phone's price point now exists only on the phone.
4. The phone's next sync pulls the desktop's version, unions there, re-stamps under its own
   node id, and pushes the union on the turn after that.

So nothing is lost, but the union reaches the other device two exchanges later. The engine
must therefore keep syncing until a pass changes nothing — **and a pass that moved only the
cursor is not a settled pass.** Watch data *and* cursor; `SyncRoundTripTest.settle` shows the
shape.

The remaining exposure: if a device is wiped between recording a price and its second sync,
that point is gone. See `plan/later.md` for the alternative that removes this.

---

## 3. Persist `SyncState` — DONE

`core/…/sync/SyncStateStore.kt` and `JsonFileSyncStateStore.kt`, one file per environment
(`sync-state$suffix.json`), written atomically through a temp file.

```kotlin
data class SyncState(
    val nodeId: String,
    val cursor: Long = 0,
    val lastPushed: Hlc = Hlc.Zero,
    val lastHlc: Hlc = Hlc.Zero,
    val lastSyncedAtMillis: Long? = null,
)
```

`pending` is gone — step 2 derives it. `lastPushed` replaces it: everything this device
stamped above that reading is still owed to the server.

`loadOrCreate()` writes a new state out **before returning it**, so the node id is fixed from
the very first launch rather than on the first sync.

`FinanceRepository.clock` now has **no default**. A default would hand out a fresh node id per
instance, which reads as a brand new device on every launch.

### A migration this turned out to need

Records stamped before the node id was persisted carry one that existed for a single launch.
`recordsToPush` recognises this device's records *by* that id, so those records would never be
offered to the server — they would sit there looking synced and never arrive.

Schema version 5 and `FinanceData.adoptedBy(clock)` re-stamp them once under the stable id.
Safe wholesale because it runs on a dataset that has never synced: before the id was stable
there was no engine, so no reading came from elsewhere and no server knows any of them.

Verified on a real device — the stamps now carry the same node id as the sync state, and a
relaunch leaves them alone.

Covered by `SyncStateStoreTest` (9) and three adoption cases in `RecordMetaTest`.

**Not yet written by anything:** `cursor`, `lastPushed` and `lastSyncedAtMillis` are stored and
read, but only the engine in step 4 will ever move them off their defaults.

---

## 4. Ktor client and `SyncEngine` — DONE

`core/…/sync/SyncApi.kt` and `SyncEngine.kt`.

`SyncApi` is an interface — `KtorSyncApi` for real, a fake for the engine's tests. Results
come back as `ApiResult`: `Ok`, `Unauthorized`, `Unreachable`, `Refused`. Returned rather
than thrown, because telling "no network" from "rejected" by exception type differs on
every platform, and the difference decides whether the app retries quietly or asks for a
password.

**No content-negotiation plugin.** `KtorSyncApi` encodes and decodes bodies itself. The
plugin would have to be installed on whatever client it is handed, and forgetting it fails
on the first call at runtime rather than at compile time.

`FinanceRepository.applyRemote` exists because remote records must **not** go through
`mutate` — that stamps whatever changed under this device's clock, which would rewrite every
incoming reading and make each pull look like a fresh local edit. Two devices would then
push newer versions at each other forever.

### Two budgets, not one

The loop has separate limits for paging (`MAX_PAGES = 500`) and for settling
(`MAX_SETTLE_PASSES = 5`). A single budget either cuts a large first sync short or lets a
feedback loop spin — they are not the same kind of "go round again".

Covered by `SyncEngineTest` (12) and the end-to-end `SyncRoundTripTest`, which now drives
the real `KtorSyncApi` against the real routes.

**Unverified:** the Darwin engine on iOS. `animaHttpClient()` names no engine and relies on
the one each target has on its classpath; that is confirmed on JVM by a test and on Android
by the build, but the iOS targets cannot be compiled on Windows.

---

## 5. Wire it into `App.kt` — DONE

### The seeding hazard, closed

`emptyFinanceData()` now stamps every seeded record — the eight default categories *and* the
settings — with `Hlc.Zero`, a reading that loses every comparison. They are a starting point
the device invented, not something the user did, so they must never win against a server.

It needed no coordination with the engine at all, which is why this shape beat "sync first,
then seed": no launch ordering to get right, correct offline, and correct whatever order two
devices happen to connect in. A seeded record the user then edits is stamped properly by
`restamped`, because at that point it *is* something they did.

Four cases in `SyncEngineTest` cover it, including the settings one — a fresh device's
default USD must not replace a chosen EUR.

### The wiring

```kotlin
val api = environment.serverUrl?.let { KtorSyncApi(animaHttpClient(), it) }
val engine = SyncEngine(repository, syncStateStore, createSecretStore(), api,
                        tokenKey = environment.sessionTokenKey)
```

- **On launch:** `engine.syncNow()` after `repository.load()`. Nothing waits on it.
- **On edit:** `FinanceRepository.localEdits` counts changes made *here* and the app asks for
  a sync when it moves. Watching the data itself would also fire for records just pulled, so
  every pull would schedule a sync with nothing to send.
- **On network regained:** not done. There is no connectivity monitoring yet; a failed sync
  waits for the next edit or the next launch.

The developer screen shows the live status and offers a manual sync.

### Verified end to end

The real desktop app, against the real server: pulled a record the server held, advanced its
cursor to 1, and left `lastPushed` at zero — which is the seeding fix visible in the field.
The server finished the exchange holding one subscription and **no categories**.

---

## What is left before this is usable

Sync works, but nothing in the app can obtain a session — `plan/next.md` item 5. Until then
every environment with a server reports `SignedOut`, which is correct behaviour and not a
fault.
