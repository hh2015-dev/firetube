# Source identity refactor (phase 1)

Only YouTube is registered. No Drive, SoundCloud, local-file adapter, OAuth flow,
new runtime dependency, or paid service is introduced by this change.

## Identity and dispatch

`Track.id` is an opaque internal storage/cache/session key. `Track.sourceId` and
`Track.externalId` identify the provider and the identifier it accepts.
`Track.identity` exposes all three together. Never send an internal key to a
provider or derive a provider URL from it.

Legacy YouTube keys deliberately remain byte-for-byte unchanged, including for
new results from the existing YouTube adapter. This preserves database foreign
keys, duplicate detection, favorites, history, cache URIs, download request IDs,
and chapter filenames. Separating the concepts does not require rekeying them.
`TrackIdentity.forSource` generates deterministic, filesystem-safe UUID-based keys
for future providers; external identifiers and display strings are not normalized.
Account-scoped identity is a future adapter requirement, not implemented here.

`AudioSource` is the minimal playback resolution contract. `StreamSource` retains
search/playlist discovery for the current UI. `AudioSourceRegistry` selects an
adapter and passes only its external ID; it returns internal IDs to the player.
`StreamResolver` gets identities from queued metadata or Room, with a legacy
YouTube fallback for old bare IDs. Missing new-format identities are errors,
never silently resolved as YouTube. Media3, the audio selection, background
service, wake mode, and URL refresh behavior remain in place.

New adapters will still require their own discovery/auth capabilities, appropriate
HTTP/content URI data sources, download policy, and Cast transport/MIME support.
The current Cast proxy remains YouTube-only; this phase merely preserves provider
metadata through its item conversion. No claim of multi-provider playback is made.

## Durable data

Room schema 3 adds `sourceId` and `externalId` to `tracks`, backfills YouTube's
external IDs in place, and changes no existing primary/foreign key. Migration 1
chains through 2 to 3. There is no destructive migration or downgrade fallback.
Schemas 1 and 2 remain committed as compatibility fixtures.

Queue JSON, download metadata, backup snapshots, Realtime Database maps and
Media3/Cast metadata now carry source information. Absent fields in old payloads
mean `youtube` and the existing ID. Backups keep their existing format/version
because this is an additive change. Older clients can still read current YouTube
payloads; do not sync future non-YouTube tracks with clients that lack providers.
Live-state persistence is unchanged from the baseline.

Downgrading to an old APK after Room migrates to schema 3 is not supported. Do not
use destructive downgrade as a rollback strategy. Keep a pre-upgrade backup for
real-device validation; no user database or download files are reset by setup.

## Validation

Use the environment activation instructions, then from `/workspace/firetube`:

```sh
source /workspace/.firetube-setup/activate.sh
./gradlew assembleDebug testDebugUnitTest lintDebug :core:extractor:test \
  --init-script /workspace/.firetube-setup/recheck/test-proxy.init.gradle \
  --no-configuration-cache --console=plain
```

The external init script configures this cloud proxy and CA trust for Robolectric;
it is environment-specific, not a project dependency. Other machines can use the
repository's standard Gradle command when direct Maven access is available.

Coverage includes real Room openings of schema 1/2 fixtures, relationship and
stats preservation, old/new backup and queue round-trips, download metadata and
index compatibility, reads of seeded legacy download/stream caches without any
provider, source routing/quality/refresh, and exact Unicode metadata preservation.
Physical-device background playback and live YouTube/Cast remain separate manual
checks; offline tests cannot prove those behaviors.

Compare the universal Debug APK in decimal MB against the baseline 32,945,041
bytes. Target 30–40 MB; investigate any increase beyond 40 MB and obtain approval
before exceeding 50 MB. Do not add padding if release shrinking yields <30 MB.

## Verified branch results

Baseline commit: `fe3c55ffad25958922335db5f46bb5796b5beb4b` (2.0.13).
Development branch: `dev/source-identities`. No application version, dependency
version, manifest permission, audio-only track selection or wake mode was changed.

| Check | Baseline | Refactor |
| --- | ---: | ---: |
| Extractor offline tests | 23 passed | 28 passed |
| App unit tests | 211 passed | 225 passed |
| Test failures / skipped in these suites | 0 / 0 | 0 / 0 |
| Android Lint | 22 warnings, 0 errors | 22 warnings, 0 errors |
| Universal Debug APK bytes | 32,945,041 | 33,108,042 |
| Universal Debug APK decimal MB | 32.945041 | 33.108042 |

Increase: 163,001 bytes (0.495%). ABIs remain arm64-v8a, armeabi-v7a,
x86 and x86_64. Version name/code remain 2.0.13/213; this is a development
build, not a release. APK SHA-256:
`3a6966aaad46ce4687ce6f06cc42ee1a379ebe893b0fe78784037a289b2e0c3b`.

Modified production files:

- Core model/contracts: `Models.kt`, `StreamSource.kt`, new `TrackIdentity.kt`
  and `AudioSource.kt` in `core/extractor/.../extractor/`.
- Composition: `AppContainer.kt`.
- Database: `data/db/Entities.kt`, `FireTubeDatabase.kt`, exported schema `3.json`.
- Durable interchange: `data/sync/PlaylistSnapshot.kt`, `CloudSync.kt`,
  `player/QueueStore.kt`, `Downloads.kt`.
- Playback boundaries: `player/MediaItems.kt`, `StreamResolver.kt`,
  `LibraryCallback.kt`, `PlaybackService.kt`, `cast/CastItemConverter.kt`.
- Sharing: `ui/components/TrackMenu.kt` only offers a known provider share URL.

New tests: `TrackIdentityTest`, `IdentityMigrationTest`, `IdentityBackupTest`,
`IdentityPersistenceTest`, `SourceRoutingTest`, `LegacyAudioCacheTest`.
No future-provider implementation or network call is included in these fixtures.
