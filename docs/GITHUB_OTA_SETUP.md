# GitHub Push & OTA Release Setup Guide

Personal Secretary uses **GitHub Actions + GitHub Releases** to build consistently signed APKs and deliver verified OTA updates directly inside the app.

## 1. One-Time GitHub Repository Setup
1. Create a new repository on GitHub (Public or Private): `https://github.com/Siroj-ai/personal-secretary`
2. Go to **Settings -> Secrets and variables -> Actions -> New repository secret** and add the 4 secrets from your local private `PersonalSecretary-Signing/GITHUB_SECRETS.txt` file:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Push this local repository and initial tag:
   ```powershell
   cd "d:\My Workspace\Personal\PersonalSecretary"
   git push -u origin main
   ```

## 2. How to Ship an OTA Update Later
Whenever you make changes to the app:
```powershell
git add .
git commit -m "Describe your improvement"
git push origin main
git tag v1.0.1
git push origin v1.0.1
```
GitHub Actions (`.github/workflows/release.yml`) will automatically run unit tests, build the signed release APK, compute its SHA-256 hash, and publish a GitHub Release.

You can also trigger a release manually in GitHub under **Actions -> Release Signed OTA APK -> Run workflow**.

## 3. Receiving OTA Updates on OnePlus 13
1. Install the **signed release APK** (`PersonalSecretary-v1.0.0-release.apk`) on your OnePlus 13.
   *(Important: if you installed an earlier debug APK, uninstall it once before installing the signed release APK so all future OTA updates match the release signature and install in-place without losing data.)*
2. Open **Health & Desk -> Secretary Setup -> App Updates (GitHub OTA)**:
   - Confirm `GitHub Repository` is `Siroj-ai/personal-secretary`.
   - If your repo is **Private**, paste a read-only fine-grained GitHub PAT (`Repository permissions -> Contents: Read-only`). Leave blank if **Public**.
   - Tap **Check for Updates**.
3. On the first OTA update, ColorOS 17 will prompt once to toggle **Allow from this source** for Personal Secretary — after that, updates download, verify SHA-256, and upgrade in-place while keeping your SQLite database, Day Book cache, Google Flow videos, and `gemma-4-E4B-it.litertlm` intact.
