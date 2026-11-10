# 𝕏 Lite (Android) — Airtel 64kbps FUP Edition

A clean, standalone, ultra-low-bandwidth native Android client for 𝕏 (Twitter) designed specifically to run seamlessly on throttled connections like **Airtel's 64 kbps / 128 kbps FUP**.

---

## 🚀 Key Features

* **100% On-Phone Compute**: Runs entirely on your Android device — zero external proxy, server, or Mac required.
* **Clean, Authentic Minimal UI**: Pure `#000000` OLED dark aesthetic matching official 𝕏, free of bloated animations or flashy distractions.
* **Instant Stale-While-Revalidate**: Feeds load instantaneously (0.01s) from local cache and refresh in the background over tiny ~3.5 KB compressed payloads.
* **Conversation Threads & Replies**: Read full tweet threads and post replies directly with minimal payloads.
* **In-App Hot Updates (OTA)**: Pull the latest updates directly in Settings without reinstalling APKs.
* **Direct Twitter GraphQL Engine**: Uses native `OkHttp` with Brotli and Gzip compression to speak directly to Twitter's internal endpoints.
* **On-Device Smart Image Engine**: Intercepts images and downscales them natively on the phone to **380px WebP (~3.5 KB)**. Photos drop from 2.5 MB to 3.5 KB, loading in under 0.4s over 64 kbps!

---

## 📥 Download APK

Grab the latest compiled APK directly from the Releases tab:
* **[Download `xlite-fup-release.apk` v1.0.2 (4.8 MB)](https://github.com/sovopr/xlite-android/releases/download/v1.0.2/xlite-fup-release.apk)**

---

## ⚙️ Setup (30 Seconds)

1. Install the APK on your phone.
2. In your desktop browser (where you are logged into [x.com](https://x.com)):
   * Open Developer Tools (`Cmd + Option + I` on Mac or `F12` on Windows).
   * Go to **Storage** (Safari) or **Application** (Chrome/Brave) &rarr; **Cookies** &rarr; `https://x.com`.
   * Copy the value of `auth_token` and `ct0`.
3. Open **𝕏 Lite** on your phone &rarr; tap **⚙ Settings** &rarr; paste the tokens &rarr; tap **Save Configuration**.
