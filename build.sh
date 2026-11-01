#!/bin/sh
# Builds XRStore.apk, the one app people install.
#
#   1. The OpenXR bridge skeleton (our code only: the shim library and the stand-in Java
#      classes). XR Store completes it on each Station with that Station's own Rokid runtime,
#      so no Rokid code is in this repository or in releases.
#   2. The Khronos OpenXR loader for Android (Apache-2.0), used to convert Quest builds.
#   3. The app, signed with $XRSTORE_KEYSTORE if set (release), else a local debug key.
#
# Needs: JDK 17, Android build-tools 35, platform 33, NDK 27 (tools/setup-mac.sh installs them).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-33/android.jar
NDK=$SDK/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64
export JAVA_HOME=${JAVA_HOME:-$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
LOADER_VERSION=1.1.65
OUT=build
rm -rf $OUT/bridge $OUT/app && mkdir -p $OUT/bridge/classes $OUT/bridge/dex $OUT/bridge/apk/lib/arm64-v8a \
	$OUT/app/classes $OUT/app/dex $OUT/app/apksig $OUT/app/assets

# 1. Bridge skeleton
$JAVA_HOME/bin/javac -source 8 -target 8 -nowarn -d $OUT/bridge/classes -cp $JAR \
	-sourcepath bridge/javastub $(find bridge/java -name '*.java')
rm -rf $OUT/bridge/classes/com/rokid/sdk/st2/gesture/ExtGestureListener.class $OUT/bridge/classes/com/rokid/input
$BT/d8 --min-api 29 --lib $JAR --output $OUT/bridge/dex $(find $OUT/bridge/classes -name '*.class')
cp $OUT/bridge/dex/classes.dex $OUT/bridge/apk/classes.dex
$NDK/bin/aarch64-linux-android29-clang -shared -fPIC -O2 -Wall -Wl,-z,max-page-size=16384 \
	-o $OUT/bridge/apk/lib/arm64-v8a/librokidxrbridge.so bridge/src/bridge.c -ldl -llog -lm
$BT/aapt2 link -o $OUT/app/assets/bridge-skeleton.apk -I $JAR --manifest bridge/AndroidManifest.xml
(cd $OUT/bridge/apk && zip -q ../../app/assets/bridge-skeleton.apk classes.dex lib/arm64-v8a/librokidxrbridge.so)

# 2. Khronos loader
AAR=$OUT/openxr_loader_for_android-$LOADER_VERSION.aar
[ -f $AAR ] || curl -sSL -o $AAR \
	https://github.com/KhronosGroup/OpenXR-SDK-Source/releases/download/release-$LOADER_VERSION/openxr_loader_for_android-$LOADER_VERSION.aar
unzip -o -q -j $AAR jni/arm64-v8a/libopenxr_loader.so -d $OUT/app/assets/khronos
cp -R app/assets/* $OUT/app/assets/

# 3. App
(cd $OUT/app/apksig && unzip -q $BT/lib/apksigner.jar 'com/android/apksig/*' && $JAVA_HOME/bin/jar cf ../apksig.jar com)
$JAVA_HOME/bin/javac -source 8 -target 8 -nowarn -d $OUT/app/classes -cp $JAR:$OUT/app/apksig.jar \
	$(find app/java -name '*.java')
$BT/d8 --min-api 29 --lib $JAR --output $OUT/app/dex $(find $OUT/app/classes -name '*.class') $OUT/app/apksig.jar
$BT/aapt2 link -o $OUT/app/base.apk -I $JAR --manifest app/AndroidManifest.xml -A $OUT/app/assets
(cd $OUT/app/dex && zip -q ../base.apk classes*.dex)
$BT/zipalign -f -p 4 $OUT/app/base.apk $OUT/app/aligned.apk
if [ -n "$XRSTORE_KEYSTORE" ]; then
	$BT/apksigner sign --ks "$XRSTORE_KEYSTORE" --ks-pass env:XRSTORE_KEYSTORE_PASS --out $OUT/XRStore.apk $OUT/app/aligned.apk
else
	[ -f $OUT/debug.keystore ] || $JAVA_HOME/bin/keytool -genkeypair -keystore $OUT/debug.keystore \
		-storepass android -alias debug -keypass android -keyalg RSA -validity 10000 -dname CN=XRStoreDebug
	$BT/apksigner sign --ks $OUT/debug.keystore --ks-pass pass:android --out $OUT/XRStore.apk $OUT/app/aligned.apk
fi
echo "built $OUT/XRStore.apk"
