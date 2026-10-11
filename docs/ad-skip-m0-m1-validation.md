# M0 and M1 delivery record

Date: 11 October 2026. Branch: `codex/ad-skip-m0-m1`.

## M0 baseline

- Upstream source: `9c7ffa16736c020045571d274aef4cdcf0878100`, version 3.12.3.
- No nested AGENTS.md files found. Application source and version unchanged for the baseline.
- Existing system Java 21 was runtime-only. Provisioned Temurin JDK 21.0.12.1 at `/tmp/antennapod-jdk21`.
- Provisioned SDK platform 36 and build-tools 36.0.0 at `/tmp/antennapod-sdk` using Google's command-line tools. Download SHA-256: `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
- Home filesystem NAME_MAX is 143; D8 generates longer filenames. Generated `app/build` is linked to `/tmp/antennapod-app-build` (NAME_MAX 255), with no source/build-version changes.
- `./gradlew :app:assembleDebug`: successful after toolchain and build-directory setup. Existing warnings remain unchanged.
- Baseline Play debug APK: `artifacts/ad-skip/m0/antennapod-baseline-play-debug.apk` (ignored by Git), SHA-256 `ea572889756257a6f1fe52e461c8ef71acfc0be435ba9fd7d177c5a6f28837df`.
- Set JAVA_HOME to `/tmp/antennapod-jdk21`, ANDROID_HOME to `/tmp/antennapod-sdk`, and prepend `$JAVA_HOME/bin` to PATH before the mandated build/install commands. Temporary toolchain/build directories need reprovisioning if removed.
- ADB server starts with authorised host access. Initially no connected device or Samsung USB device was detected. Owner is enabling USB debugging and connecting the phone.
- Samsung install/model/Android, baseline smoke checks, pilot data preservation and authenticated native HTTPS connectivity are pending. M0 exit is not yet achieved.

## M1 integration

Implementation and validation are in progress. Phone transport, real episode processing, seek behaviour and offline/restart acceptance have not yet been demonstrated.
