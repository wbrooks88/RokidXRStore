#!/bin/sh
# Installs what build.sh needs on a Mac with Homebrew: JDK 17, apktool (for make-uxr.sh),
# adb, and the Android command-line tools with build-tools 35, platform 33 and NDK 27.
set -e
command -v brew >/dev/null || { echo "Homebrew is required: https://brew.sh"; exit 1; }
brew install openjdk@17 apktool android-platform-tools
brew install --cask android-commandlinetools
export JAVA_HOME=$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
SDK=$(brew --prefix)/share/android-commandlinetools
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
	"build-tools;35.0.0" "platforms;android-33" "ndk;27.2.12479018" >/dev/null
echo "Ready: ./build.sh"
