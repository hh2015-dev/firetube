# Local audio implementation and verification

Base: `dev/source-identities`, commit `30ca85e`.
Work branch: `dev/local-audio`. No main changes, remote push, merge or release.

## Data-safety gate (before local implementation)

The existing 253 offline tests plus `UserLibrarySafetyTest` passed before local
production code was added. The new fixture creates a real schema-2 SQLite library
with 120 legacy YouTube tracks, 10 playlists (including Favorites and a deletion
tombstone), 400 ordered playlist memberships, 360 history rows, 120 listening
records, 20 Media3 download-index entries and a 20-item saved queue.

After Room opens/migrates the fixture, every pre-existing column of every library
table is compared with its original value, including row IDs, ordering, Unicode,
YouTube IDs and foreign-key references. `PRAGMA foreign_key_check` is empty. The
download index is reopened independently and its IDs, URIs and metadata remain
unchanged. Queue position/order, backup export/restore, and sync map conversion
also round-trip. This is synthetic user data, not a user's private library.

The existing tests additionally exercise old download/stream cache bytes without
network resolution and legacy backup/queue/download payloads lacking new fields.
Cloud sync verification covers serialization and local merge/restore paths, not
live Firebase service authentication or network behavior.

## Implementation

- Library > **On this device** opens `LocalAudioScreen`. It requests
  `READ_MEDIA_AUDIO` on Android 13+, or `READ_EXTERNAL_STORAGE` on earlier Android
  (manifest permission capped at SDK 32). A settings link handles permanent denial.
- `local/LocalAudio.kt` queries MediaStore's external audio collection on IO,
  including removable volumes. It reads original display name, title, artist,
  album, duration and MIME. It excludes pending/trashed rows where supported, but
  does not restrict `IS_MUSIC`, so recordings and podcasts are not silently hidden.
- The system media scanner performs tag decoding. Blank titles fall back to the
  original filename. No transliteration, Unicode normalization or guessed
  re-decoding is applied. Ambiguous/damaged legacy tags may still require editing
  in a tag editor; there is no reliable universal encoding repair.
- `LocalAudioViewModel` refreshes on screen resume and debounced ContentObserver
  events. Permission loss and query errors produce explicit UI states. Scans
  upsert metadata; missing files never delete playlist/history/library rows.
- Source `local` is registered beside YouTube in the existing registry. Stable
  identities include the volume/row URI and generation-added (or date-added on
  older Android) to detect reused MediaStore IDs. A device binding is stored in
  `noBackupFilesDir`, not in cloud/manual backups. Foreign-device references fail
  safely rather than play an unrelated file. Rescan and re-add music after moving
  to another device or reinstalling the app; automatic relinking is not provided.
- Media3 still owns playback through the same service/player/session. Local items
  use `firetube://local/<internal-id>`; `LocalPlaybackDataSource` resolves these to
  validated MediaStore content URIs and reads with ContentDataSource. Both audio
  caches are bypassed. Range/position reads operate on the original descriptor.
  Files are not copied to private storage or downloaded again.
- Access and generation are checked on every open. Missing/replaced files,
  permission revocation and a file disappearing between query/open are translated
  into Media3 IO errors. Local errors pause and retain the queue; the UI reports
  an unavailable/unsupported file instead of deleting records.
- Local tracks use the existing favorite, playlist, queue and history operations.
  Room schema 4 adds nullable album/fileName columns in place. Migrations 1->2->3->4
  retain old keys; schema-3 migration is tested too. Album/fileName survive Room,
  queue JSON, backup/sync maps and Media3 metadata. Old payloads default them to null.
- Local downloads are disabled/no-op because the bytes are already on the device.
  The Cast button is unavailable for queues containing local files; attempts to
  enqueue local audio into an active Cast session are blocked with a message.
  Local Chromecast serving is not implemented in this phase.
- YouTube extraction, its HTTP source and cache keys, audio-only selection, wake
  mode and foreground service declarations remain unchanged. Existing YouTube
  paths use the same cached factory behind the new local/non-local routing layer.

## Tests and results

| Check | Previous branch | Local audio branch |
| --- | ---: | ---: |
| Extractor offline tests | 28 | 28 passed |
| App unit/integration tests (Robolectric) | 225 | 239 passed |
| Total | 253 | 267 passed |
| Failures / skipped in these suites | 0 / 0 | 0 / 0 |
| Android Lint | 22 warnings, 0 errors | 22 warnings, 0 errors |
| Universal Debug APK, bytes | 33,108,042 | 32,999,668 |
| Decimal MB | 33.108042 | 32.999668 |

New coverage includes the realistic safety fixture, schema 3->4, modern/legacy
permissions, Unicode metadata and filenames in an RTL Compose semantics tree,
MediaStore query contracts through a test provider, missing/recycled or
foreign references, permission loss, file-open races, and mixed local/YouTube
library/backup/queue persistence. Five small actual audio samples pass through the
local data-source route to Media3 MP3/M4A/FLAC/OGG/WAV extractors. A real ID3v2.3
UTF-16 title preserves Arabic, Hebrew, Greek and English. This verifies parsing
and content reads, not hardware decoding or audible playback.

There are no new final Lint warnings. Intermediate UnsafeOptInUsage errors were
fixed by declaring the Media3 API opt-ins; URI style and permission-helper warnings
were fixed at their call sites without disabling Lint. Two low-priority test-only
compiler deprecations remain for the existing style of Compose test rule and the
MP4 extractor constructor. They do not alter shipped runtime behavior.

No new runtime dependency, codec pack, FFmpeg library, paid service, Drive or
SoundCloud integration was added. Synthetic samples are test resources only;
inspection confirms they are not inside the APK. Existing GPLv3 and dependency
license obligations remain in place.

The final APK was regenerated from a missing generated output to avoid incremental
ZIP padding. Its payload entries total 32,678,661 bytes versus 32,636,241 previously
(+42,420). Thus the physical size reduction of 108,374 bytes (0.327%) is packaging
layout, not a claim that the new feature reduces code size. Both are Debug 2.0.13,
versionCode 213, for arm64-v8a/armeabi-v7a/x86/x86_64. `apksigner verify` succeeded.
SHA-256: `c5160304ba896a0538593b75c5b8fd65c1383657db351026c51334b52398032a`.
Target 30–40 MB met; the 50 MB approval limit was not approached.

Reproduce from the cloud environment with its existing proxy setup:

```sh
source /workspace/.firetube-setup/activate.sh
./gradlew assembleDebug testDebugUnitTest lintDebug :core:extractor:test \
  --init-script /workspace/.firetube-setup/recheck/test-proxy.init.gradle \
  --no-configuration-cache --console=plain
```

## Remaining device checks and limitations

No actual Android phone/emulator audio output, live YouTube playback, live Firebase
sync, permission dialog, OS MediaStore indexing or OEM battery behavior was tested.
Robolectric uses a controlled content provider and real SQLite/files. MP3, M4A,
FLAC, OGG and WAV decoding still depends on actual codec/format/device support;
unsupported or corrupt files report playback errors. Compose semantics establish
exact text content, not font/glyph correctness on every device.

Before deployment, test Android 8/12/13/14+ permissions and revocation, actual files
with legacy tags, SD-card removal, rename/move/delete, seeking, mixed queues,
headset/audio focus, screen-off/Doze/background playback and process restart. Files
not yet indexed by Android are not found until the media scanner indexes them.
If a move changes row identity, existing playlist references are retained but may
need manual re-adding. Device-bound references intentionally do not auto-resolve
across devices. Returning to an older APK after schema 4 is not supported; no
lossy downgrade/reset fallback is installed. Keep a backup before device upgrade.

## Changed-file map

- Model: `core/extractor/.../Models.kt`, `TrackIdentity.kt`.
- Composition: `AppContainer.kt`; permissions: `app/src/main/AndroidManifest.xml`.
- Local discovery/state: new `local/LocalAudio.kt`, `LocalAudioViewModel.kt`.
- Playback: new `player/LocalPlaybackDataSource.kt`; `MediaStack.kt`, `MediaItems.kt`,
  `PlaybackService.kt`, `PlayerConnection.kt`, `Downloads.kt`, `QueueStore.kt`.
- Storage/interchange: `data/LibraryRepository.kt`, `data/db/Entities.kt`,
  `FireTubeDatabase.kt`, new exported schema `4.json`, `data/sync/PlaylistSnapshot.kt`.
- UI: new `ui/library/LocalAudioScreen.kt`; `LibraryScreens.kt`, `FireTubeRoot.kt`,
  `PlayerUi.kt`, `components/TrackMenu.kt`; strings in all three shipped locales.
- Tests: new `UserLibrarySafetyTest`, `LocalAudioTest`, `LocalAudioDisplayTest`;
  extended `IdentityMigrationTest`, `IdentityPersistenceTest`; synthetic format
  samples and their provenance under `app/src/test/resources/local-audio/`.
