# Durable notification inbox: stage 2

## Storage and ownership

`PersistentNotificationStore` uses the existing AndroidX DataStore dependency.
It atomically writes one versioned JSON snapshot to the app-private
`datastore/device_notification_inbox.json` file. No JWT is stored here.
Android's app backup is already disabled by the manifest.

Open the store with the device UUID from trusted enrollment. This ID only scopes
local storage; HTTP inbox identity still comes exclusively from the device JWT.
The production Context delegate provides one DataStore instance per application.
Opening for a different enrolled device clears the previous local inbox. Old
store handles reject reads and mutations while the other device owns storage.
The Android notification ID counter survives device changes.

## State transitions

1. `saveFetched(batch)` persists a full page before any display or ack. It keeps
   the first payload and expiry for an existing server ID. Duplicate fetches never
   reset delivery state or extend retention.
2. `pendingDisplay()` returns READY_TO_DISPLAY entries without consuming them.
   A blocked/failed Android delivery must leave these entries unchanged.
3. After Android accepts delivery, call `markDisplayed(id)`. The entry becomes
   AWAITING_ACK and is returned by `pendingAcknowledgements()` until completed.
4. On ack 204, call `markAcknowledged(id)`. On ack 404, call
   `markAcknowledgementNotFound(id)`. Both remove the payload and preserve a
   terminal ID tombstone until expiry. Transient failures leave AWAITING_ACK intact.
   Terminal states cannot regress; ack before display is rejected.

Each entry has a persisted Android integer ID allocated separately from its server
UUID. Do not hash UUIDs or use the existing manager's fresh-ID helper for inbox
retries: the future presentation adapter must use the stored integer ID. After a
crash between Android delivery and `markDisplayed`, resubmission uses the same ID,
replacing the existing OS item rather than creating a second one. Exactly-once
sound/display across this crash window is not guaranteed.

## Retention and capacity

- Normal entries expire 7 days after server creation; test entries expire after
  24 hours. Future timestamps are additionally capped by first receipt plus TTL.
- After successful ack or 404 only the ID, Android ID, expiry and terminal state
  remain; title, body, category and other payload fields are removed.
- Expiry applies to every state, including failed deliveries and ack retries.
  Opening the store and all operational reads/writes prune expired records.
  The future coordinator should also call `cleanup()` on its maintenance cycle.
- Default hard limits: 10,000 records and 8 MiB of serialized snapshot data.
  Tombstones count toward both limits. Limits can be lowered for tests.
- Unexpired IDs and pending work are never silently evicted. If a complete batch
  cannot fit, `NotificationStoreCapacityException` propagates and none of that
  batch commits. Expired entries can free space.
- Before EVERY consuming GET (including a full-page drain), the future single poll
  coordinator must call `canFetchPage()`. It conservatively reserves 100 entries
  and the API's bounded response plus local metadata. Pause polling when false.
  This check is not a reservation against competing pollers; only one coordinator
  is allowed. It cannot prevent physical disk exhaustion after a server fetch.

Disk errors and malformed/version-unknown local data propagate. There is no
corruption-reset handler that could silently erase pending work or dedupe history.
DataStore's atomic update keeps the previous snapshot on a failed write.

## Integration boundary

The store performs no HTTP or Android side effects itself. The delivery layer
persists receipt state around presentation/ack, and [automatic polling](NOTIFICATION_POLLING.md)
now connects enrollment identity, capacity preflight, cleanup and retry scheduling.

The server still has an at-most-once fetch boundary: losing a response before
local persistence cannot be repaired by this store or by an ack retry.

## Tests

From `android/`: `./gradlew :app:testDebugUnitTest`
(Windows: `.\gradlew.bat :app:testDebugUnitTest`).

Tests use actual temporary-file DataStores: reopen/recovery, duplicate and
concurrent saves, stable IDs, durable ack retries, message removal, TTL boundaries,
capacity and preflight, device isolation, corrupted state, and a write that fails
after emitting partial bytes. On the Windows host, tests use DataStore OkioStorage for portable atomic file replacement, with the same production serializer and state logic. No live notification endpoint is called.
