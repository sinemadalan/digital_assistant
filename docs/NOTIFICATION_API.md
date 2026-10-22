# Device notification API and storage: stages 1 and 2

The current backend contract is `notification-contract.md`. It delivers notifications
through `GET /v1/notifications/pending`, independently of upload responses.
The older embedded `alert` integration described in NOTIFICATIONS.md is still present
for now; it is not the inbox transport required by this contract.

## Implemented

- `ServerNotification` preserves the server UUID, open-ended category, severity,
  title, body, creation time and test flag. It is separate from `AppNotification`,
  which describes Android presentation.
- `NotificationsApiClient.fetchPending(token)` returns a typed list from HTTP 200.
- `NotificationsApiClient.acknowledge(token, id)` accepts only HTTP 204, without
  parsing a response body.
- The caller supplies the current enrolled device token from `NativeTokenStore`.
  No panel credentials or inbox-selection device ID are used.
- The HTTPS transport disables redirects, limits response bytes, sets timeouts,
  discards error bodies and does not log tokens or notification contents.
- 401 and 404 are distinct results. Network/timeout, 429 and 5xx are distinguishable
  retry outcomes. Retry-After supports delay-seconds and HTTP dates.
- Malformed payloads fail the response rather than inventing missing fields.
  Unknown categories and additive JSON fields are accepted; severity is validated.

## Durable inbox (stage 2)

The persistent device-scoped store now saves batches atomically, preserves stable
Android IDs, deduplicates server IDs, tracks display and ack state, and prunes
expired entries. Completed messages become minimal ID tombstones.
See [NOTIFICATION_STORAGE.md](NOTIFICATION_STORAGE.md) for the state machine,
7-day/24-hour retention, 10,000-record/8-MiB limits and capacity preflight.

## Integration boundary

There is still no live fetch, polling timer, network retry loop, token mutation
or connection to Android presentation. Each API call performs at most one request.
Before connecting the API to the existing notification manager, wire the single
coordinator to check capacity before every fetch, persist before display, reuse
stable Android IDs and acknowledge only after Android accepts delivery.
The old embedded upload-alert integration remains unchanged for now.

## Verification

Run Android unit tests from `android/`:
`./gradlew :app:testDebugUnitTest` (Windows: `.\gradlew.bat :app:testDebugUnitTest`).
The notification API and HTTP transport tests use fakes; they never contact the live inbox.
