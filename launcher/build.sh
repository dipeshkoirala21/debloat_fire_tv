#!/usr/bin/env bash
# Builds launcher/out/tvhome.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs the Android SDK (build-tools + a platform, API 30 or newer) and a JDK 11-21.
set -euo pipefail
cd "$(dirname "$0")"

for tool in javac keytool zip; do
  command -v $tool >/dev/null || { echo "$tool not found: install a JDK 17 and zip (see README)." >&2; exit 1; }
done
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK ]]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools; do
    [[ -d $d ]] && SDK=$d && break
  done
fi
[[ -n $SDK && -d $SDK ]] || { echo "Android SDK not found; set ANDROID_HOME." >&2; exit 1; }
# Newest installed build-tools and platform.
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
[[ -n $BT && -n $JAR ]] || { echo "Install build-tools and a platform with sdkmanager." >&2; exit 1; }

OUT=out
rm -rf $OUT && mkdir -p $OUT/res $OUT/classes

"$BT"/aapt2 compile --dir res -o $OUT/res/
"$BT"/aapt2 link -o $OUT/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --java $OUT/gen $OUT/res/*.flat --min-sdk-version 25 --target-sdk-version 30
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$JAR" -classpath "$JAR" \
  -d $OUT/classes $(find src $OUT/gen -name '*.java')
"$BT"/d8 --min-api 25 --lib "$JAR" --output $OUT $(find $OUT/classes -name '*.class')
(cd $OUT && zip -qj unsigned.apk classes.dex)
"$BT"/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

# A local signing key is created on first build (it is git-ignored; keep it to update in place).
KS=keystore.jks
[[ -f $KS ]] || keytool -genkeypair -keystore $KS -storepass tvhome -keypass tvhome -alias tvhome \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Home" >/dev/null 2>&1
"$BT"/apksigner sign --ks $KS --ks-pass pass:tvhome --key-pass pass:tvhome --out $OUT/tvhome.apk $OUT/aligned.apk
echo "Built $(pwd)/$OUT/tvhome.apk"
