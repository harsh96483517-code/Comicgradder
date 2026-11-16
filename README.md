# Comic Grabber

Android app (Kotlin + Jetpack Compose, Material 3, dark mode) that browses a comic site in a WebView,
lets you multi-select comics on listing pages and downloads them one-by-one through a queue.

Min SDK 24 · Target SDK 34 · Saves to `Downloads/ComicGrabber/`

## How it works
1. **Browse** – WebView loads your start URL (changeable with the pencil icon).
2. **Detect** (each card gets a blue ⬇ download button top-left and a tick-box top-right)
 – `assets/inject.js` runs on every page. Pages with ≥2 comic cards are *listing* pages;
   a tick-box is overlaid on each card. Selection is kept across pages.
3. **Queue** – tap **Download Selected (X)**. Comics are processed sequentially by `DownloadEngine`.
4. **Per comic**
   1. Fetch the comic page with the WebView's cookies + user-agent.
   2. Find the site's own Download link (`?dl=` URL → "Download" text link → direct `.zip/.rar/.pdf` link).
   3. Follow it (up to 3 hops); if the response is a file, stream it to Downloads as `<Comic title>.<ext>`.
   4. **Fallback**: if no usable link, scrape all images (all pages of the post) and write `<Comic title>.zip`.
5. **Progress** – a bar under the toolbar shows `n/total · current title`; tap it for the full queue.

## Build
* Android Studio (Hedgehog+ / JDK 17): open the folder → Sync → *Build ▸ Build APK(s)*.
* CLI: `gradle wrapper --gradle-version 8.5 && ./gradlew assembleDebug`
* GitHub: push the repo – the included workflow builds the APK (Actions ▸ artifact).

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Customising for a site
Everything site-specific is in two places:
* `app/src/main/assets/inject.js` – `CARD` selector and `titleLink()` (what counts as a comic card)
* `DownloadEngine.findDownloadLink()` / `collectImages()` – where the download link / images are

## Notes
* Keep the app open while the queue runs.
* Use only for content you have the right to download.
