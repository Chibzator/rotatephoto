# RotateFix

A floating orientation toggle for phones with dead motion sensors.

When any camera app opens, a round bubble appears. Tap it until the arrow
points to the sky, then shoot. A second or two after each photo is saved,
RotateFix rewrites the photo's EXIF orientation tag to match. Pixels are not
touched, so there is no quality loss.

- **Tap**: Portrait → Landscape (top left) → Landscape (top right)
- **Long-press**: off (✕) for photos you don't want touched
- **Drag**: move the bubble anywhere
- Resets to portrait each time the camera opens
- Only touches JPEG/PNG/WebP photos in DCIM, taken while the bubble was up

## Get the APK (no Android Studio needed)

1. Create a new **private** repository on GitHub and upload this whole folder
   (keep the `.github` folder; on github.com use "uploading an existing file"
   and drag the folder contents in).
2. Open the repo's **Actions** tab. The "Build APK" run starts on its own
   (about 4 minutes).
3. Open the finished run, download **RotateFix-apk** (a zip), unzip it, and
   copy `app-debug.apk` to the phone.
4. On the phone, open the APK and allow installing from that source.

## Or build with Android Studio

Open this folder, let it sync, then **Run** with the phone connected
(USB debugging on).

## First launch

Grant the four permissions in the app (overlay, all files access,
notifications, unrestricted battery), then tap **Start**. It restarts by itself
after a reboot.

The notification has a **Show / hide bubble** button in case the bubble ever
doesn't appear on its own.

## Limits

- Videos are not corrected.
- RAW (DNG) files are skipped.
- If Google Photos backs up a photo in the 1–2 seconds before the fix, the
  backed-up copy may keep the old orientation.
