# Security Policy

X Lite takes user security and account session hygiene seriously. Because the client interacts directly with authenticated X (`x.com`) endpoints using user-provided session credentials, X Lite enforces defense-in-depth boundaries at the native Kotlin bridge layer.

---

## Supported Versions

Only the latest release branch actively receives security patches:

| Version | Supported |
| :---    | :---      |
| `1.0.x` | Yes       |
| `< 1.0` | No        |

We strongly encourage users to run the latest signed GitHub release or update through the in-app **Check & Install Updates** flow.

---

## Architecture & Security Model

X Lite incorporates multiple proactive isolation layers:

1. **Strict Host & Endpoint Allowlist (`XLitePolicy.kt` & `TwitterBridge.kt`)**:
   - The native WebView `@JavascriptInterface` bridge rejects arbitrary network requests.
   - GET, GraphQL/REST POST, form POST, signed GET, OTA web updates, APK downloads, and external links are validated against explicit scheme (`https://` only) and host/path rules (`x.com`, `api.x.com`, `gist.githubusercontent.com`, `github.com/sovopr/xlite-android/`, GitHub release CDNs).
   - Arbitrary redirects, cross-host credential leakage, and invalid `x-client-transaction-id` strings containing control characters or unreasonable lengths are rejected.
2. **Scoped Navigation & Image Interception (`MainActivity.kt`)**:
   - WebView navigation is restricted to the bundled asset (`file:///android_asset/index.html`) and staged OTA file (`filesDir/live_index.html`); external URLs must pass `XLitePolicy.isAllowedExternalUrl()` to open via OS intent.
   - Image requests routed through `https://local.xlite/img` only fetch from `https://pbs.twimg.com` (`thumb`, `rich`, `hd` modes), decode/transcode bounded bitmaps, and cache them atomically in `cacheDir/images/`.
   - Live OTA web updates (`filesDir/live_index.html`) use atomic temporary writes with a single-tap fallback (**Revert to Bundled Version**).
3. **Device Storage & Credential Isolation (`AndroidManifest.xml`)**:
   - `android:allowBackup="false"` disables automatic ADB/cloud backups of app-private `SharedPreferences` (`auth_token`, `ct0`), offline JSON timeline/bookmark caches (`cacheDir/json_store/`), and image caches.
   - No third-party analytics SDKs, crash reporters, ad networks, or tracking pixels are included.
4. **SHA-256 Verified Releases & OTA APK Updates**:
   - Official GitHub Releases publish `SHA256SUMS.txt` alongside `xlite-fup-release.apk`.
   - `TwitterBridge.installApkUpdateVerifiedAsync()` enforces semantic non-downgrade checks and verifies SHA-256 file digests against `SHA256SUMS.txt` (or an explicit expected digest) before invoking `FileProvider` package installation.

---

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

If you believe you have discovered a vulnerability in X Lite, submit a report privately through GitHub Security Advisories:

1. Navigate to the [Security Advisories](https://github.com/sovopr/xlite-android/security/advisories) tab on GitHub.
2. Click **"Report a vulnerability"** to submit your advisory directly to the repository maintainer.
3. This opens a secure, private advisory workspace where maintainers can review, discuss, and coordinate a patch before public disclosure.

### What to Include in Your Report
To help us triage and resolve the issue quickly, please provide:
- A description of the vulnerability and its potential impact.
- Clear reproduction steps or a minimal proof-of-concept (PoC).
- Your environment details (Android OS version, X Lite APK version / commit SHA).
- Any proposed mitigations or fixes, if known.

### Response Timeline
- **Initial Acknowledgement**: Within 48 hours of receipt.
- **Triage & Assessment**: Within 5 business days.
- **Remediation & Advisory**: We will work with you to develop and verify a fix, and coordinate public disclosure once a patched release is published.

Thank you for responsibly disclosing security issues and helping keep X Lite users safe.
