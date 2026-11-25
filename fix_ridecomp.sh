#!/usr/bin/env bash
set -euo pipefail

echo "=== [1/6] Cek folder project ==="
if [ ! -f "app/build.gradle.kts" ]; then
  echo "ERROR: Jalankan script ini di dalam folder root project (Ridecomp-main)!"
  exit 1
fi

echo "=== [2/6] Hapus verification-metadata.xml & gradle.lockfile ==="
rm -f gradle/verification-metadata.xml
find . -name "gradle.lockfile" -delete
echo "OK: verification-metadata.xml dan semua gradle.lockfile dihapus."

echo "=== [3/6] Matikan LeakCanary di app/build.gradle.kts ==="
if grep -qE '^[[:space:]]*debugImplementation\(libs\.leakcanary\.android\)' app/build.gradle.kts; then
  sed -i 's/^\([[:space:]]*debugImplementation(libs\.leakcanary\.android)\)/\/\/\1/' app/build.gradle.kts
  echo "OK: leakcanary dikomentari di app/build.gradle.kts."
else
  echo "OK: leakcanary sudah dimatikan sebelumnya."
fi

echo "=== [4/6] Bersihkan DevTools.kt (debug) ==="
DEBUG_DEVTOOLS="app/src/debug/kotlin/com/ridecomp/app/DevTools.kt"
RELEASE_DEVTOOLS="app/src/release/kotlin/com/ridecomp/app/DevTools.kt"

if [ -f "$RELEASE_DEVTOOLS" ]; then
  cp "$RELEASE_DEVTOOLS" "$DEBUG_DEVTOOLS"
  echo "OK: DevTools.kt debug ditimpa dengan versi release."
elif [ -f "$DEBUG_DEVTOOLS" ]; then
  # Hapus import leakcanary & shark
  sed -i '/^import leakcanary\./d' "$DEBUG_DEVTOOLS"
  sed -i '/^import shark\./d' "$DEBUG_DEVTOOLS"
  # Hapus blok LeakCanary.config = ... sampai baris penutup )
  sed -i '/LeakCanary\.config/,/)/d' "$DEBUG_DEVTOOLS"
  echo "OK: Referensi LeakCanary & Shark dibersihkan dari DevTools.kt."
fi

REMAINING=$(grep -rniE "leakcanary|shark" app/src/ || true)
if [ -n "$REMAINING" ]; then
  echo "PERINGATAN: Masih ada sisa referensi leakcanary/shark:"
  echo "$REMAINING"
  exit 1
else
  echo "OK: 0 sisa referensi leakcanary/shark di app/src/."
fi

echo "=== [5/6] Hapus embel-embel 'Debug' pada nama aplikasi ==="
FILES_WITH_DEBUG=$(grep -rli "RideComp Debug" app/ || true)
if [ -n "$FILES_WITH_DEBUG" ]; then
  echo "$FILES_WITH_DEBUG" | xargs sed -i 's/RideComp Debug/RideComp/Ig'
  echo "OK: 'RideComp Debug' diubah menjadi 'RideComp'."
else
  echo "OK: Tidak ada teks 'RideComp Debug' tersisa."
fi

echo "=== [6/6] Buat GitHub Actions Workflow (Auto-Publish ke Tab Releases) ==="
mkdir -p .github/workflows
cat << 'EOF' > .github/workflows/android-release.yml
name: Build & Publish RideComp APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build APK
        run: ./gradlew :app:assembleDebug --no-daemon --dependency-verification=off

      - name: Prepare APK file
        run: |
          APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
          if [ -z "$APK_PATH" ]; then
            echo "APK tidak ditemukan!"
            exit 1
          fi
          cp "$APK_PATH" RideComp.apk
          ls -lh RideComp.apk

      - name: Publish to GitHub Releases
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0-build-${{ github.run_number }}
          name: RideComp Build #${{ github.run_number }}
          body: |
            Build otomatis RideComp (tanpa LeakCanary & tanpa embel-embel Debug).
            Langsung download file **RideComp.apk** di bawah ini.
          files: RideComp.apk
          make_latest: true
EOF

echo "OK: Workflow .github/workflows/android-release.yml berhasil dibuat."
echo ""
echo "=== SELESAI! Cek perubahan dengan 'git status', lalu jalankan: ==="
echo "git add -A && git commit -m \"fix build, hapus leakcanary & auto publish release\" && git push"
