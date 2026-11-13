# Contributing to X Lite

Thank you for your interest in contributing to X Lite! Whether you are reporting a bug, proposing new features, improving documentation, or submitting pull requests, your contributions are welcome.

Please take a moment to review this document to ensure a smooth collaboration.

---

## Code of Conduct

By participating in this project, you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md). Please report any unacceptable behavior to the maintainers.

---

## How Can I Contribute?

### 1. Reporting Bugs
- Check the [existing issues](https://github.com/sovopr/xlite-android/issues) before opening a new one to avoid duplicates.
- Use the **[Bug report](ISSUE_TEMPLATE/bug_report.md)** template.
- Include clear reproduction steps, environment details (Android version, device model, app version/SHA, network type), and screenshots if visual. Never paste real session cookies (`auth_token` or `ct0`).

### 2. Suggesting Features
- Open an issue using the **[Feature request](ISSUE_TEMPLATE/feature_request.md)** template.
- Describe the proposed feature, the bandwidth/usability problem it solves, and any alternatives you have considered.

### 3. Improving Documentation
- Corrections to `README.md`, setup guides, code comments, or architectural documentation are always appreciated.

### 4. Contributing Code
- Start by discussing larger changes in an issue before writing code.
- Follow the development workflow outlined below.

---

## Development Setup

### Prerequisites
- **Java Development Kit**: JDK 17 (`java -version`)
- **Android SDK**: API 34 (`compileSdk = 34`, `minSdk = 24`)
- **Node.js**: 22.x or newer (`node -v`, zero third-party npm packages required)
- **Git**

### Getting the Code
```bash
git clone https://github.com/sovopr/xlite-android.git
cd xlite-android
```

### Keeping Version Numbers Consistent
Before bumping a release, ensure the semantic version matches across both:
- `android/app/build.gradle.kts` (`appVersion` default / `versionCode`)
- `android/app/src/main/assets/index.html` (`APP_VERSION`, `LATEST_APK_VERSION`)

`scripts/verify-assets.cjs` enforces this automatically in CI.

---

## Testing & Quality Assurance

Ensure all JavaScript asset checks and Kotlin JVM unit tests pass before opening a pull request.

### Asset & Web UI Verification (Node.js stdlib)
```bash
node scripts/verify-assets.cjs
node scripts/feed-media.test.cjs
node scripts/search-pagination.test.cjs
node scripts/post-actions.test.cjs
```

### Android Unit Tests, Lint & Build (Gradle)
```bash
cd android
chmod +x gradlew
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

---

## Pull Request Guidelines

1. **Branch Naming**: Use descriptive branch names:
   - `fix/issue-description`
   - `feat/feature-name`
   - `docs/clarification`
2. **Commit Style**: Use clear, concise commit messages following the [Conventional Commits](https://www.conventionalcommits.org/) format (e.g. `feat: verify apk updates with sha256`, `fix: handle quoted retweet text`).
3. **Keep PRs Focused**: Avoid bundling unrelated changes into a single pull request.
4. **Pass All Status Checks**: All PRs must pass deterministic CI checks before being merged.
5. **Fill out the Pull Request Template**: Provide context on what changed and how you tested it.

---

## Project Structure Overview

```
xlite-android/
├── android/                                  # Self-contained Gradle & Kotlin Android project
│   ├── app/
│   │   ├── build.gradle.kts                  # Android SDK 24-34, OkHttp/Brotli & JUnit deps
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml       # Minimal permissions & FileProvider
│   │       │   ├── assets/index.html         # Self-contained WebView SPA + GraphQL/REST client
│   │       │   ├── java/com/sovopr/xlite/    # MainActivity, TwitterBridge & XLitePolicy
│   │       │   └── res/                      # App icons, colors, styles & FileProvider paths
│   │       └── test/java/com/sovopr/xlite/   # JUnit4 policy, semver & media unit tests
│   ├── gradle/                               # Gradle wrapper JAR and version properties
│   ├── build.gradle.kts                      # Root Android Gradle plugin config
│   ├── gradle.properties
│   ├── gradlew
│   └── settings.gradle.kts
├── scripts/                                  # Node verification & deterministic UI test harnesses
├── docs/                                     # Brand SVG assets, screenshots, security & release notes
└── .github/                                  # CI, CodeQL, release workflow, community docs & templates
```

Thank you for contributing to X Lite!
