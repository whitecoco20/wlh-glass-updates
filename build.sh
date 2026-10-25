#!/bin/sh
# Builds WLH Glass from app/. No Gradle, no Android Studio.
# Ubuntu/Debian/WSL:  sudo apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk
# First run creates your own signing key (glass.keystore). Keep it: updates only install over
# a copy signed with the same key. Never commit it.
set -e
cd "$(dirname "$0")"
AJ=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KS=${KEYSTORE:-glass.keystore}
if [ ! -f "$KS" ]; then
  echo "Creating your signing key ($KS). Pick a password and remember it."
  keytool -genkeypair -keystore "$KS" -alias glass -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=WLH Glass"
fi
B=build; rm -rf $B; mkdir -p $B/obj $B/gen
aapt package -f -m -J $B/gen -M app/AndroidManifest.xml -S app/res -I "$AJ"
javac -source 8 -target 8 -bootclasspath "$AJ" -d $B/obj $B/gen/com/wlh/glass/R.java app/src/com/wlh/glass/*.java
dalvik-exchange --dex --min-sdk-version=23 --output=$B/classes.dex $B/obj
aapt package -f -M app/AndroidManifest.xml -S app/res -I "$AJ" -F $B/unsigned.apk
(cd $B && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 $B/unsigned.apk $B/aligned.apk
apksigner sign --ks "$KS" --out $B/wlh-glass.apk $B/aligned.apk
echo "Built $B/wlh-glass.apk"
