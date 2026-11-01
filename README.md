# XR Store for the Rokid Station 2

Run OpenXR apps and WebXR games on a **Rokid Station 2** with Rokid glasses (tested with the
Max 2). XR Store is one Android app that:

- **sets up OpenXR** on the Station (once), using Rokid's own runtime that ships on the device;
- is a **store front**: rows of WebXR games and experiences (Wolvic's picks, heyVR, dmnshd.gg,
  itch.io browser games, the three.js demos and more, ~600 in all) that open in the
  [Wolvic](https://wolvic.com) XR browser, plus OpenXR apps to install;
- **installs any OpenXR APK** so it runs on the Station, including many Quest builds;
- keeps its catalogue **up to date by itself** (daily, in the background).

Nothing on the Station's system is modified: no root, no unlocked bootloader. Everything is
ordinary apps, and uninstalling them puts the Station back as it was.

## Install

1. Download `XRStore.apk` from [Releases](../../releases).
2. Install it on the Station, either way:
   - from a computer: `adb install XRStore.apk` (enable USB debugging in the Station's
     developer options first), or
   - on the Station: download it in Chrome and open it; allow Chrome to install apps when asked.
3. Open **XR Store** from the Station's app grid. The **Setup** tab walks you through:
   1. allowing XR Store to install apps;
   2. installing the official Khronos OpenXR runtime broker;
   3. building the OpenXR bridge from your Station's own Rokid runtime (takes a few seconds);
   4. ticking **XR Store OpenXR bridge** in the broker;
   5. installing Wolvic.

Android asks you to confirm each install; that prompt can't be skipped.

## Using it

- **Store**: scroll the rows and tap a card. Web experiences open in Wolvic (on itch.io pages,
  press *Run game*); apps install. **Your XR apps** at the top launches what's installed.
- **Install an APK**: pick an OpenXR APK, or open a downloaded APK with XR Store.
- **Controls** in XR apps: the Station is a 3DoF pointer. Tap the touchpad to click, long-press
  for menu, swipe to re-center the pointer.

## What works, and what can't

- Works: OpenXR apps built with the standard Khronos loader (e.g. Godot, Unity OpenXR, Wolvic,
  hello_xr), and Quest builds that use OpenXR (XR Store swaps Meta's loader for Khronos's).
- Doesn't: old Quest apps built on Meta's VrApi (pre-OpenXR), apps that need hand tracking or
  6DoF controllers, and Android XR apps that need Google's Android XR system.
- Google Play Protect may refuse re-signed copies of apps it knows (XR Store has to re-sign apps
  to add the Rokid tag).
- A Rokid system update may change their runtime. XR Store notices and offers to rebuild the
  bridge. Updates could also tighten the rules this relies on; if so, this may stop working
  until it is adapted.

## How it works

Rokid's OpenXR runtime (`com.rokid.openxr.runtime`, Monado-based) is on every Station 2 but
can't be used by ordinary OpenXR apps as shipped. The **bridge** (`bridge/`) fixes that: a small
runtime shim that loads Rokid's library, works around its packaging bugs, maps the Station's
controller to the standard simple/Touch controller profiles, turns touchpad taps into clicks,
and hides features the runtime advertises but can't provide. XR Store assembles the bridge on
your Station from the shim plus **your Station's own** Rokid runtime, so no Rokid code is in this
repository or in releases. Apps are given the Rokid `com.rokid.sdk=uxr` tag (without it the
Station won't let them draw on the glasses) and signed with a key generated on your device.

## Building

    tools/setup-mac.sh     # once: JDK 17, Android build tools, NDK (Homebrew)
    ./build.sh             # -> build/XRStore.apk (debug key; set XRSTORE_KEYSTORE for release)

`tools/make-uxr.sh` adapts an APK on a computer the same way XR Store does on the Station.

## Third-party components

- Khronos OpenXR loader for Android (Apache-2.0), bundled for converting Quest builds.
- apksig from the Android SDK build tools (Apache-2.0), bundled for signing on the device.
- Downloaded at setup from their official releases: the Khronos OpenXR runtime broker, Wolvic.
