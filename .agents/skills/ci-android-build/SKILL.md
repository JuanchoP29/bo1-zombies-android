---
name: ci-android-build
description: >-
  Use when building, packaging, or releasing an APK for bo1-zombies-custom.
  Explains that all Android/NDK/DXVK builds run on GitHub Actions CI
  (build-apk.yml), not locally on Windows, and provides exact portable tool
  paths (mingit, gh, adb) and release steps.
---

# Compilación y Release por GitHub Actions CI (`bo1-zombies-custom`)

En este equipo Windows **no hay compilador local** (`javac`, `clang`, NDK ni Android SDK). Todas las compilaciones del APK se ejecutan en **GitHub Actions** mediante `.github/workflows/build-apk.yml`.

## Herramientas portables en este PC
- **Git:** `C:\Users\JuanchoP\.gemini\antigravity\scratch\tools\mingit\cmd\git.exe`
- **GitHub CLI:** `C:\Users\JuanchoP\.gemini\antigravity\scratch\tools\gh\bin\gh.exe` (o `gh` en PATH, cuenta `JuanchoP29`)
- **ADB:** `C:\platform-tools\adb.exe` (`192.168.1.21:5555` Wi-Fi / `e42c32fe` USB)
- **Carpeta de APKs y firma:** `C:\BO1_Custom_APK\` (keystore: `C:\BO1_Custom_APK\keystore\bo1-custom-release.p12`)

## Flujo estándar para compilar una nueva versión (`customXX`)
1. Actualizar `versionCode` y `versionName` en `android/AndroidManifest.xml` (ej. `0.5-turnip-custom24`).
2. Hacer `git add`, `git commit` y `git push origin main` usando `mingit`.
3. Verificar y monitorear el workflow `Build Custom Android APK` con `gh run list --limit 3` / `gh run watch` (o usar `schedule` para esperas sin bloquear).
4. Al terminar el CI, descargar el artefacto APK en `C:\BO1_Custom_APK\`, verificar firma / firmar con el keystore release si aplica, e instalar encima sin desinstalar (`adb install -r`).
5. Actualizar `PROJECT_STATUS.md` con el nuevo commit, tag (`v0.5-turnip-customXX`), `versionCode` y resultados de prueba.
