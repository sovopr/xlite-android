# X Lite for Android

![X Lite logo](logo.svg)

X Lite is a small Android client for reading and posting on X over slow mobile connections. It uses a native network bridge and a lightweight web interface. Requests go directly from the phone to X; there is no project-operated proxy or account service.

The app is designed with Airtel's throttled 64 kbps FUP connection in mind. Actual speed and data use depend on the network and X's responses.

## What it does

| Feature | Behavior |
| --- | --- |
| Home | Following and For You timelines, with cached posts for offline reading, a visible refresh indicator, and the same feed position when returning from a conversation. |
| Search | Keyword, account, and hashtag search, eight results at a time with **Load More**. |
| Conversations | Open a post, read replies, and reply. |
| Posting | Compose a text post. |
| Images | Previews load on tap by default; image loading can also be turned off or made automatic. Failed previews can be retried. |
| Video and GIFs | Small poster frames on tap, plus available descriptions and duration. Video files are not fetched in the feed. |
| Updates | Install web interface fixes from **Settings → Check for Updates**. |

The app keeps a small feed page size and uses compressed HTTP responses where X supports them. Search on the original v1.0.2 APK uses its existing POST bridge. Newer APKs use GET search with X's transaction header; the setup data is reused for 15 minutes.

## Install and set up

1. Download the [latest APK](https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk) on your Android phone and install it.
2. In a browser already signed in to [x.com](https://x.com), open Developer Tools and find the `auth_token` and `ct0` cookies for `x.com`.
3. In X Lite, open **Settings**, enter those two values, and tap **Save Configuration**.
4. Open **Settings → Check for Updates** to get the latest web interface.

These cookies grant access to your X account. Keep them private. X Lite stores them in the app's private Android storage and sends them to X with requests. If they expire or you sign out, enter fresh values in Settings.

### APK version and web interface version

Settings shows both versions. A web interface update changes the screens and JavaScript over-the-air (~150 KB). Native Android changes (bridge methods, image proxy rules, permissions, or icons) require updating the APK. Earlier APKs (`<= v1.0.17`) opened the release APK download link externally; starting with **v1.0.18**, tapping **Check & Install Updates** in Settings automatically updates **both** — downloading the signed APK inside the app with live progress over 64 kbps and opening Android's install prompt (`REQUEST_INSTALL_PACKAGES` + `FileProvider`) when native changes exist, or installing a quick OTA reload when only the UI changed.

Starting with **v1.0.17**, GitHub release APKs are signed with one persistent release keystore configured in CI secrets so future APK updates (`v1.0.17` -> `v1.0.18+`) install cleanly in-place without losing your cookies. Earlier APKs (`<= v1.0.16`) used an ephemeral debug signing key, so upgrading onto `v1.0.17+` from `v1.0.16` or older may require saving your `auth_token` and `ct0` cookies and uninstalling the old APK once. Once `v1.0.18` is installed, the in-app **Check & Install Updates** button handles full app updates end-to-end.

## Using less data

- Leave image mode on **Tap to load** or choose **Off** in Settings.
- Profile pictures load lazily for visible tweets (`Mini` 24px ~1.6 KB avg, `Standard` 48px ~2.4 KB avg) and are cached permanently on disk by URL so repeat views use 0 bytes; choose **Initials only** (or **Text only** image mode) for strict 0 KB.
- Use **Offline** to read the cached feed without a new request.
- Use **Load More** only when you need another page of posts or search results.
- Video and GIF posts show a single poster frame on request. The app does not start MP4 playback on a 64 kbps connection. In a controlled 64 kbps transfer test, an 18-second 267 kbps MP4 took 74 seconds; three short clips still took 10 seconds. A pre-generated three-frame animation took 1.4 seconds, but the feed data used here supplies one poster image, so creating that animation on the phone would require fetching video data first.
- Search results and timeline data still come from X, so a slow or unstable connection can delay or interrupt a request. A failed search page can be retried without clearing the results already shown.

## Build locally

Install JDK 17 and the Android SDK for API 34, then run:

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. GitHub Actions also builds an APK on pushes to `main` and publishes one when a version tag is pushed.

The web interface is in [`app/src/main/assets/index.html`](app/src/main/assets/index.html). Android networking and local storage are in [`app/src/main/java/com/sovopr/xlite`](app/src/main/java/com/sovopr/xlite). A developer with their own X cookies can run `node scripts/live-search-smoke.cjs` with `XLITE_TEST_AUTH_TOKEN` and `XLITE_TEST_CT0` set in the environment. Do not commit cookies or paste them into issues.

## Project status

X Lite depends on X's private web endpoints, which can change without notice. Search query IDs and response formats may need updates. The project is independent and is not affiliated with X or Airtel.
