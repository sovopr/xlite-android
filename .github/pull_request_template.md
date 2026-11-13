## Description

Please provide a clear and concise summary of the changes made in this pull request and the rationale behind them.

Fixes #(issue)

---

## Type of Change

Please delete options that are not relevant:

- [ ] Bug fix (non-breaking change fixing an issue)
- [ ] New feature (non-breaking change adding functionality)
- [ ] Breaking change (fix or feature causing existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Refactoring or performance optimization
- [ ] CI / Build configuration update

---

## Areas Affected

- [ ] Kotlin Native Bridge / Security Policy (`android/app/src/main/java/com/sovopr/xlite/`)
- [ ] WebView SPA UI & GraphQL/REST Client (`android/app/src/main/assets/index.html`)
- [ ] Verification & Unit Tests (`android/app/src/test/`, `scripts/*.test.cjs`)
- [ ] Documentation / CI / Workflows (`README.md`, `docs/`, `.github/`)

---

## Checklist

- [ ] My code adheres to the project's coding and style guidelines.
- [ ] I have performed a self-review of my own code.
- [ ] I have updated version constants (`android/app/build.gradle.kts`, `android/app/src/main/assets/index.html`) if preparing a release.
- [ ] I have updated relevant documentation where appropriate.
- [ ] All asset & JS tests pass locally (`node scripts/verify-assets.cjs`, `node scripts/*.test.cjs`).
- [ ] All Kotlin unit tests & lint pass (`(cd android && ./gradlew testReleaseUnitTest lintRelease)`).
