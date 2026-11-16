#!/usr/bin/env bash
# Builds HumanClicker.apk without Gradle / Android Studio.
# Needs: JDK 11+, aapt, dalvik-exchange (dx), zipalign, apksigner
#   Ubuntu/Debian: sudo apt-get install -y aapt dalvik-exchange zipalign apksigner openjdk-17-jdk-headless
set -euo pipefail
cd "$(dirname "$0")"

B=build
JAR=${ANDROID_JAR:-$B/android.jar}
DX=$(command -v dalvik-exchange || command -v dx)
mkdir -p "$B"
if [ ! -f "$JAR" ]; then
  echo "Downloading android.jar (API 34)..."
  curl -fL -o "$JAR" https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
fi

rm -rf "$B/gen" "$B/classes" "$B/apk"
mkdir -p "$B/gen" "$B/classes" "$B/apk"

echo "[1/5] resources"
aapt package -f -m -J "$B/gen" -M app/src/main/AndroidManifest.xml -S app/src/main/res -I "$JAR" \
  -F "$B/apk/app.unsigned.apk"

echo "[2/5] javac"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$B/classes" \
  $(find app/src/main/java "$B/gen" -name '*.java')

echo "[3/5] dex"
"$DX" --dex --min-sdk-version=26 --output="$B/apk/classes.dex" "$B/classes"
(cd "$B/apk" && aapt add -f app.unsigned.apk classes.dex >/dev/null)

echo "[4/5] zipalign"
zipalign -f -p 4 "$B/apk/app.unsigned.apk" "$B/apk/app.aligned.apk"

echo "[5/5] sign"
KS=${KEYSTORE:-keystore/humanclicker.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass humanclicker -keypass humanclicker -alias hc \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Human Clicker" >/dev/null
fi
apksigner sign --ks "$KS" --ks-pass pass:${KS_PASS:-humanclicker} --ks-key-alias hc \
  --min-sdk-version 26 --out HumanClicker.apk "$B/apk/app.aligned.apk"
apksigner verify HumanClicker.apk
ls -la HumanClicker.apk
