# FireTube personal audio document picker (experimental)

## What changed
- Library > On this device: **Select audio from Files or Google Drive**.
- Android's ACTION_OPEN_DOCUMENT picker supplies the document URI. Google Drive is
  offered only if its Android DocumentsProvider is installed and configured.
- Multiple selected items are added to the existing local Room library and
  immediately queued with the existing Media3 player.
- Only persistable **read** grants are requested; no Google OAuth, Firebase,
  Cloud billing, public links, or private files committed to GitHub.
- Document playback checks the exact persisted grant again on each open, uses
  ContentDataSource, and bypasses the YouTube cache. No extra download or Cast.
- Existing YouTube/MediaStore identity, Room schema, queue, and backup formats
  are retained. It does not enumerate the complete Drive account.

## Limitations / acceptance gate
- This is an implementation candidate, not a verified Google Drive integration.
- The Drive provider may download, buffer, or expose non-seekable data; direct
  streaming and seeking must be tested on an Android phone with Drive configured.
- The user must pick a file; no in-app Google account login or Drive directory
  browser exists. Files without a compatible MIME type or read grant are skipped.
- A moved/deleted file or revoked grant can no longer play. Player should pause
  and keep the queue. The app does not create public links or change file sharing.
- The repository is public. Do not publish APKs, secrets, OAuth tokens, or file
  metadata from a real Google account in CI logs or artifacts.
- Debug APK is compiled privately on runner only, until a private delivery
  method is approved.
- Requires successful Android CI and on-device validation before feature is
  declared working.

## Manual device verification
1. Ensure Google Drive is signed in on the phone.
2. In Library > On this device tap "Select audio from Files or Google Drive".
3. In the system picker select a private MP3 file in Drive.
4. Verify playback, background playback, playback seeking, screen-off playback,
   and file name display, and verify that no full-file copy is made by FireTube.
5. Restart the app and phone; play the same item via Favorites/recent history.
6. Revoke Drive permissions or remove file; verify playback fails without
   exposing private metadata and keeps existing favorites and queue.
7. Test mixed Hebrew/Arabic/Greek names and multiple selections.
8. Repeat for a local document; confirm previous YouTube content still works.

Use no paid Google Cloud services, no Work, and no Google Play publishing.
