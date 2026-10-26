# Lifeboat Photo Vault 🛟🧱

**If Google had done it right the first time, nobody would need a Lifeboat.**

Get your photos out of Google's reach — and keep them out.

Lifeboat Photo Vault is a free, open-source Android app that pulls your photos and videos out of Google Photos (or off your phone or SD card) and puts them behind a wall. Google Photos, the phone maker's gallery app, cloud backup and the "empty trash" button can't get through that wall.

It exists because of a real loss. Someone moved years of photos from Google Photos to an SD card, emptied the Google Photos trash, and the photos on the SD card vanished too, because Google Photos treats the files on your device and its cloud copies as the same photos.

## What it does

| | |
|---|---|
| **Rescue my Google Photos** | Walks you through Google's export page (Takeout), then imports the downloaded `.zip` files directly. You don't unzip anything or move files by hand. Real "taken" dates come from Google's metadata files. |
| **＋ Add** | Pick photos and videos (from the phone, the SD card or Google Photos), a whole folder such as `DCIM` on the SD card, or Takeout zips. |
| **🛟 Recover deleted photos** | Put the SD card in a USB card reader plugged into the phone (OTG). Lifeboat reads the whole card, finds deleted JPEG/PNG/HEIC/MP4/MOV files by their internal structure, and puts them in the vault. It's **read-only**: the only commands it can send are SCSI reads (TEST UNIT READY, INQUIRY, READ CAPACITY, READ, REQUEST SENSE). No root needed, because it uses Android's USB host API. The phone's own card slot can't be read like this without root. |
| **Safe to delete?** | Pick photos you want to delete in Google Photos. Lifeboat compares each one byte for byte with the vault and tells you which ones are safe to delete. |
| **Back up** | Writes one password-locked `.lifeboat` file to a USB stick or computer. **Restore** brings it back on any phone. |
| **Save copies out** | Normal JPG and MP4 copies, filed into year folders. |
| **Check every file** | Re-reads every file and compares it with its original fingerprint. |
| **Phone or SD card** | The vault can live on either one, and you can move it later. |

## The wall

- **No internet permission.** The app can't upload, sync or phone home, because Android enforces it. Check for yourself: Settings → Apps → Lifeboat → Permissions.
- **Invisible to Google Photos and gallery apps.** Vault files are encrypted (AES-256-GCM), have random names with no extensions, and sit in the app's private storage. On the SD card that's the private `Android/data/…` folder, which the media scanner skips.
- **No cloud backup.** `allowBackup=false` plus data-extraction rules keep Android and Google backup out.
- **Verified copies.** Each file is encrypted, read back, decrypted and compared with the original's SHA-256 fingerprint before it counts as saved.
- **Trash-proof.** Deleting or emptying a trash in Google Photos, Gallery or Files can't reach the vault.
- **Locked.** Uses the phone's screen lock or fingerprint. Screenshots and the recent-apps preview are blocked.
- **Master key** is random and sealed by the phone's hardware keystore. It never goes to the SD card.

⚠️ **One rule:** Android deletes an app's data, including its SD-card folder, when you uninstall the app. Keep a backup file.

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
