# 𝕏 Lite (Android) — Airtel 64kbps FUP Edition

A standalone, ultra-low-bandwidth native Android client for 𝕏 (Twitter) designed specifically to run seamlessly on throttled connections like **Airtel's 64 kbps / 128 kbps FUP**.

---

## 🚀 Key Features

* **100% On-Phone Compute**: Runs entirely on your Android device — zero external proxy, server, or Mac required.
* **Instant Stale-While-Revalidate**: Feeds load instantaneously (0.01s) from local cache and refresh in the background over ~3.5 KB compressed payloads.
* **In-App Hot Updates (OTA)**: Tap `🔄 Update` inside the app to pull the latest frontend code directly from the repository without reinstalling the APK!
* **Fixed Usernames & Handles**: Full compatibility with modern 2026 Twitter GraphQL user schemas across original tweets and retweets.
* **Fixed "For You" Feed**: Dedicated `HomeTimeline` query ID with schema validation and on-demand pagination.
* **Direct Twitter GraphQL Engine**: Uses native `OkHttp` with Brotli and Gzip compression to speak directly to Twitter's internal endpoints.
* **On-Device Smart Image Engine**: Intercepts images and downscales them natively on the phone's GPU/CPU to **380px WebP at 30% quality (~3.5 KB)**. Photos drop from 2.5 MB to 3.5 KB, loading in **under 0.4 seconds** over 64 kbps!
* **OLED Pitch Black Theme**: Pure `#000000` dark mode to maximize text contrast and save phone battery life.

---

## 📥 Download APK

Grab the latest compiled APK directly from the Releases tab:
* **[Download `xlite-fup-release.apk` v1.0.1 (4.8 MB)](https://github.com/sovopr/xlite-android/releases/download/v1.0.1/xlite-fup-release.apk)**

---

## ⚙️ Setup (30 Seconds)

1. Install the APK on your phone.
2. In your desktop browser (where you are logged into [x.com](https://x.com)):
   * Open Developer Tools (`Cmd + Option + I` on Mac or `F12` on Windows).
   * Go to **Storage** (Safari) or **Application** (Chrome/Brave) &rarr; **Cookies** &rarr; `https://x.com`.
   * Copy the value of `auth_token` and `ct0`.
3. Open **𝕏 Lite** on your phone &rarr; tap **⚙️ Settings** &rarr; paste the tokens &rarr; tap **Save Configuration**.
4. Future updates can be applied with 1-tap by clicking the **🔄 Update** button in the header bar!

---

## 🛠️ Automated CI/CD (GitHub Actions)

This repository includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`). Every time changes are pushed to `main`, GitHub Actions automatically:
1. Compiles the native Kotlin source with Android SDK 34 and JDK 17.
2. Builds the release APK.
3. Automatically publishes a new GitHub Release with the APK attached.
