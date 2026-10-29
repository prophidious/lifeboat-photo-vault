# Lifeboat 🛟🧱

**Get your photos out of Google's reach, and keep them out.**

Lifeboat is a free, open-source Android app that pulls your photos and videos out of Google Photos (or off your phone or SD card) and keeps them as normal files where Google Photos, cloud backup and the "empty trash" button can't reach them, while you still can.

It exists because of a real loss. Someone moved years of photos from Google Photos to an SD card, emptied the Google Photos trash, and the photos on the SD card vanished too, because Google Photos treats the files on your device and its cloud copies as the same photos.

## What it does

| | |
|---|---|
| **＋ Add** | From the phone, from the SD card, or from Google Photos (pick just the ones you want). You can also hit **Share → Lifeboat** inside Google Photos. **ALL of Google Photos** walks you through Google's export page (Takeout) and imports the downloaded `.zip` files directly, with real "taken" dates from Google's metadata. |
| **⬇ Get out** | Select some photos or all of them, then **Copy** or **Move** them to any folder on the phone, SD card or USB drive as normal files sorted by year. Every copy is read back and checked before it counts, and before anything is removed on a move. |
| **Delete safely** | Deleting a photo in Lifeboat removes only Lifeboat's copy, never copies you made elsewhere. |
| **🛟 Recover deleted photos** (⋮ More) | Put the SD card in a USB card reader plugged into the phone (OTG). Lifeboat reads the whole card, finds deleted JPEG/PNG/HEIC/MP4/MOV files by their internal structure, and brings them in. It's **read-only**: it only sends SCSI reads. No root needed. |
| **Safe to delete?** (⋮ More) | Pick photos you want to delete in Google Photos. Lifeboat compares each one byte for byte and tells you which ones are safe to delete. |
| **Back up / Restore** (⋮ More) | Writes one password-locked `.lifeboat` file to a USB stick or computer. Restore brings it back on any phone. |
| **Check every file** (⋮ More) | Re-reads every file and compares it with its original SHA-256 fingerprint. |

## The wall

- **Your photos, your files.** Photos are kept as normal files in a folder *you* pick (`Lifeboat/YYYY/MM-Month/`). You can copy them to a laptop with a cable like any other folder.
- **Hidden from Google Photos.** Each folder gets a `.nomedia` file, so Google Photos and gallery apps don't scan it or back it up. Keep the folder out of `DCIM`.
- **No internet permission.** The app can't upload, sync or phone home, because Android enforces it. Check for yourself: Settings → Apps → Lifeboat → Permissions.
- **No servers, no accounts.** We keep nothing about you.
- **No cloud backup.** `allowBackup=false` plus data-extraction rules keep Android and Google backup out.
- **Verified copies.** Every file is read back and compared with the original's SHA-256 fingerprint before it counts as saved.
- **Trash-proof.** Emptying the trash in Google Photos can't reach Lifeboat's folder.
- **Optional encryption (coming).** A password, AES-256-GCM encryption and offline 2FA, all off by default. There are no servers, so a lost password means locked photos can't be recovered.

Older versions (≤0.2) kept an encrypted vault in the app's private storage. On first launch, 0.3 offers to convert it to normal files and checks every copy first.

### Why the import goes through Takeout
Since March 2025, Google no longer lets third-party apps read your whole Google Photos library through its API. Google Takeout is the only complete way out, so Lifeboat makes it a few taps and does the rest itself.

## Lost photos already? `tools/rescue.py`
A desktop recovery tool for SD cards and drives. It uses only Python 3, no installs needed. It reads every sector, finds JPEG/PNG/HEIC/MP4/MOV files by their internal structure, and writes them out sorted into year folders without duplicates.

```bash
python tools/rescue.py image /dev/sdX card.img      # 1. raw copy of the card (safest)
python tools/rescue.py scan  card.img Recovered      # 2. pull deleted photos/videos out
python tools/rescue.py sort  recup_dir Sorted        # tidy up PhotoRec/DiskDigger output
```
**Stop using the card first.** Every new write can overwrite deleted photos. Never save the recovered files onto the same card.

## Build

```bash
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # crypto + Takeout parsing tests
```
Release signing reads `LIFEBOAT_KEYSTORE`, `LIFEBOAT_KEYSTORE_PASSWORD`, `LIFEBOAT_KEY_ALIAS` and `LIFEBOAT_KEY_PASSWORD` from the environment, so no secrets live in the repo.

Min Android 10 (API 29). Tested target: Android 15 (API 35). Motorola first; it works on any Android phone.

## Vault file format (`LBV1`)
`"LBV1" | fileId(16) | chunks…`. Each chunk is `flag(1) | len(4) | AES-256-GCM(ct+tag)` over 1 MiB of plaintext. Per-file key = `HMAC-SHA256(master, "lifeboat-file"|fileId)`, nonce = `flag | chunkIndex`, AAD = `fileId | index | flag`. Reordered, dropped or truncated chunks fail authentication.

## License
MIT
