# Personal Secretary (Android)

A privacy-first native Android (Kotlin + Jetpack Compose + Material 3) executive secretary and financial, physical, and mental health consultant built for flagship Android devices (OnePlus 13 / `arm64-v8a`).

## Features
- **Financial Health (`Day Book` Sync & Hygiene):** Syncs via Google Apps Script Web App (`?action=bootstrap` / `add_transaction`) or CSV/TSV/JSON import, with deterministic deduplication, abbreviation normalization, running-balance anomaly detection, local auto-categorization, Credit Card & SSF net-equity tracking.
- **Physical Health (Steps):** Reads **Android Health Connect** (`StepsRecord` + background read) with a live hardware **`Sensor.TYPE_STEP_COUNTER`** fallback and OxygenOS battery optimization helper.
- **Mental Health & Correlations:** Private 15-second mood/energy/stress/sleep check-ins stored locally in backup-excluded SQLite (`allowBackup="false"`) with cross-domain stress-vs-spending and steps-vs-mood correlation detection.
- **On-Device Intelligence (`LiteRT-LM` + `gemma-4-E4B-it.litertlm`):** Runs local Gemma 4 E4B with GPU acceleration (`Backend.GPU()`) + CPU fallback, grounded strictly in verified deterministic metrics (plus an instant Deterministic Consultant fallback when no model is loaded).
- **Google Flow Secretary Presence:** Optional 4-slot realistic executive MP4 video loops (`At Desk`, `Reviewing Dossier`, `Delivering Briefing`, `Executive Alert`) played locally via Media3 ExoPlayer.
- **GitHub Releases OTA Self-Updater:** Built-in in-app update checker, SHA-256 checksum verification, private/public repo support, and `FileProvider` package installer handoff.

## Building Locally
```bash
./gradlew testDebugUnitTest assembleDebug
```
For signed OTA-compatible release builds, see [docs/GITHUB_OTA_SETUP.md](docs/GITHUB_OTA_SETUP.md) and [docs/APPS_SCRIPT_BRIDGE.md](docs/APPS_SCRIPT_BRIDGE.md).
