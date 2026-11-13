# Dependency Security & Supply Chain Audit

X Lite minimizes supply-chain exposure by keeping the bundled web client completely free of third-party npm packages and relying on a small set of pinned AndroidX, Material, and Square OkHttp artifacts.

---

## Summary Status

- **Web Client & Verification Scripts (`app/src/main/assets/index.html`, `scripts/*.cjs`)**: Zero runtime or development npm dependencies. All tests and static checks use the built-in Node.js standard library (`node:assert`, `node:vm`, `node:fs`, `node:path`).
- **Android Native Dependencies (`app/build.gradle.kts`)**: Pinned exact artifact versions in Gradle with weekly Dependabot update monitoring and GitHub CodeQL analysis for both `java-kotlin` and `javascript-typescript`.

---

## Pinned Dependencies

### Android Runtime (`implementation`)

| Artifact | Pinned Version | Role |
| :--- | :--- | :--- |
| `androidx.core:core-ktx` | `1.13.1` | Core Kotlin extensions, `FileProvider`, `PackageInfoCompat` |
| `androidx.appcompat:appcompat` | `1.6.1` | Activity lifecycle and dark theme compatibility (API 24+) |
| `com.google.android.material:material` | `1.11.0` | Material theme primitives and color surfaces |
| `com.squareup.okhttp3:okhttp` | `4.12.0` | HTTP/2 connection pooling, GraphQL/REST transport, OTA downloads |
| `com.squareup.okhttp3:okhttp-brotli` | `4.12.0` | Transparent Brotli (`br`) decompression for compact JSON timelines |

### Android Unit Testing (`testImplementation`)

| Artifact | Pinned Version | Role |
| :--- | :--- | :--- |
| `junit:junit` | `4.13.2` | JVM unit tests (`XLiteUnitTest.kt`) |

---

## Verification & Monitoring Controls

- **Static Asset & Leak Scan (`scripts/verify-assets.cjs`)**: Verifies embedded `<script>` syntax in `index.html`, version consistency across Gradle and web assets, XML/SVG integrity, image magic bytes, and scans all source files for accidental private keys or session token strings.
- **APK Digest Verification (`SHA256SUMS.txt` & `TwitterBridge.kt`)**: GitHub release workflows emit cryptographic SHA-256 digests (`SHA256SUMS.txt`), and the in-app updater validates downloaded APK digests via `MessageDigest("SHA-256")` before installation.
- **Automated Supply-Chain Hygiene**: `.github/dependabot.yml` tracks Gradle (`/` and `/app`) and GitHub Actions version bumps weekly; `.github/workflows/dependency-submission.yml` submits the resolved Gradle dependency tree (direct and transitive artifacts) to GitHub's Dependency Graph with Dependabot security alerts enabled; `.github/workflows/codeql.yml` runs static security analysis across Java/Kotlin and JavaScript on pushes and pull requests.
