#!/bin/sh
# Marks an OpenXR app as a Rokid "UXR" app so the Station 2 lets it draw on the
# glasses: adds <meta-data android:name="com.rokid.sdk" android:value="uxr"/> to
# <application>, then re-signs it with the bridge's debug key.
#
# Quest builds carry Meta's OpenXR loader, which only finds Meta's runtime. If the app's
# libopenxr_loader.so isn't the Khronos one, it is replaced with ../loader's Khronos loader,
# and <queries> for the Khronos runtime broker are added so the loader can reach it.
# Run ./build.sh first (it provides the Khronos loader and the debug key).
#   make-uxr.sh in.apk [out.apk]
# The result is signed with a different key, so uninstall the original first.
set -e
IN=$1; OUT=${2:-${IN%.apk}-uxr.apk}
[ -f "$IN" ] || { echo "usage: $0 in.apk [out.apk]"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
export JAVA_HOME=${JAVA_HOME:-$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH=$JAVA_HOME/bin:$PATH
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
apktool d -q -s -f -o "$TMP/src" "$IN"
python3 - "$TMP/src/AndroidManifest.xml" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
if 'android:name="com.rokid.sdk"' not in s:
    s = re.sub(r'(<application\b[^>]*?)(/?)>', lambda m: m.group(1) + '>' +
        '<meta-data android:name="com.rokid.sdk" android:value="uxr"/>' +
        ('</application>' if m.group(2) else ''), s, count=1)
    open(p, 'w').write(s)
PY
KHRONOS=$HERE/../build/app/assets/khronos/libopenxr_loader.so # from ./build.sh
APP_LOADER=$TMP/src/lib/arm64-v8a/libopenxr_loader.so
if [ -f "$APP_LOADER" ] && ! grep -q org.khronos.openxr.runtime_broker "$APP_LOADER"; then
	echo "replacing Meta's OpenXR loader with the Khronos loader"
	cp "$KHRONOS" "$APP_LOADER"
	python3 - "$TMP/src/AndroidManifest.xml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
if 'org.khronos.openxr.runtime_broker' not in s:
    q = ('<queries><provider android:authorities="org.khronos.openxr.runtime_broker;'
         'org.khronos.openxr.system_runtime_broker"/><intent><action '
         'android:name="org.khronos.openxr.OpenXRRuntimeService"/></intent></queries>')
    s = s.replace('<application', q + '<application', 1)
    open(p, 'w').write(s)
PY
fi
apktool b -q -o "$TMP/unsigned.apk" "$TMP/src"
$BT/zipalign -f -p 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"
$BT/apksigner sign --ks "$HERE/../build/debug.keystore" --ks-pass pass:android --out "$OUT" "$TMP/aligned.apk"
echo "$OUT"
