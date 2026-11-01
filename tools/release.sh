#!/bin/sh
# Builds XRStore.apk signed with the release key and creates a DRAFT GitHub release
# (publish it on GitHub after checking it).
#   tools/release.sh 1.0
# The key lives outside the repository: $XRSTORE_KEYSTORE (default
# ~/Source/RokidXRStore-release-key/xrstore-release.p12), password in the macOS Keychain under
# "rokidxrstore-release". Every update must be signed with this same key; back it up.
set -e
cd "$(dirname "$0")/.."
VERSION=${1:?usage: tools/release.sh <version>}
export XRSTORE_KEYSTORE=${XRSTORE_KEYSTORE:-$HOME/Source/RokidXRStore-release-key/xrstore-release.p12}
export XRSTORE_KEYSTORE_PASS=$(security find-generic-password -w -s rokidxrstore-release)
grep -q "android:versionName=\"$VERSION\"" app/AndroidManifest.xml \
	|| { echo "app/AndroidManifest.xml versionName isn't $VERSION"; exit 1; }
./build.sh
cp build/XRStore.apk build/XRStore-$VERSION.apk
(cd build && shasum -a 256 XRStore-$VERSION.apk > XRStore-$VERSION.apk.sha256)
gh release create "v$VERSION" --draft --title "XR Store $VERSION" \
	--notes "Install XRStore-$VERSION.apk on a Rokid Station 2, open it and follow the Setup tab. See the README." \
	build/XRStore-$VERSION.apk build/XRStore-$VERSION.apk.sha256
