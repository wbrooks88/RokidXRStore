package io.github.wbrooks88.xrstore;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the OpenXR bridge APK on this Station.
 *
 * Rokid's OpenXR runtime (com.rokid.openxr.runtime) can't be used by ordinary OpenXR apps as
 * shipped: its manifest names a library file that doesn't exist, its Java code is only found
 * next to the copy of the library that is loaded, and it aborts when unloaded. The bridge
 * (bridge/ in the source) fixes that. Its APK is our skeleton (assets/bridge-skeleton.apk:
 * the shim library and stand-in classes) plus this Station's own runtime library and classes,
 * copied here, so no Rokid code is ever distributed.
 *
 * When a Rokid update changes the runtime, the bridge has to be rebuilt; {@link #upToDate}
 * compares the runtime with the one the installed bridge was built from.
 */
final class BridgeBuilder {
	static final String RUNTIME = "com.rokid.openxr.runtime";
	static final String BRIDGE = "io.github.wbrooks88.xrstore.bridge";

	private BridgeBuilder() {}

	/** True if the Station has Rokid's OpenXR runtime at all. */
	static boolean runtimePresent(Context c) {
		try {
			c.getPackageManager().getApplicationInfo(RUNTIME, 0);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	/** Identifies the runtime build: its version and the size and date of its APK. */
	static String runtimeFingerprint(Context c) {
		try {
			PackageInfo p = c.getPackageManager().getPackageInfo(RUNTIME, 0);
			File apk = new File(p.applicationInfo.sourceDir);
			return p.getLongVersionCode() + "/" + apk.length() + "/" + apk.lastModified() + "/" + p.lastUpdateTime;
		} catch (PackageManager.NameNotFoundException e) {
			return "";
		}
	}

	static boolean installed(Context c) {
		try {
			c.getPackageManager().getPackageInfo(BRIDGE, 0);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	/**
	 * Installed and built from the runtime that is on the Station now. The bridge carries the
	 * runtime it was built from in its versionName, so this survives XR Store being reinstalled.
	 */
	static boolean upToDate(Context c) {
		try {
			String built = c.getPackageManager().getPackageInfo(BRIDGE, 0).versionName;
			return versionName(c).equals(built);
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	/** "rokid-" plus a short hash of the runtime fingerprint. */
	private static String versionName(Context c) {
		return "rokid-" + Integer.toHexString(runtimeFingerprint(c).hashCode());
	}

	/** Builds and signs the bridge APK into {@code out}. */
	static void build(Context c, File out, File work, ApkTagger.Signer signer) throws Exception {
		ApplicationInfo rt = c.getPackageManager().getApplicationInfo(RUNTIME, 0);
		File unsigned = new File(work, "bridge-unsigned.apk");
		try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(unsigned))) {
			// Our part, with a versionCode that always increases so a rebuild installs as an update.
			try (ZipInputStream skeleton = new ZipInputStream(c.getAssets().open("bridge-skeleton.apk"))) {
				for (ZipEntry e; (e = skeleton.getNextEntry()) != null; ) {
					byte[] data = read(skeleton);
					if (e.getName().equals("AndroidManifest.xml")) {
						AxmlPatcher.Options o = new AxmlPatcher.Options();
						o.tag = false;
						o.versionCode = (int) (System.currentTimeMillis() / 60000L);
						o.versionName = versionName(c);
						data = AxmlPatcher.patch(data, o);
					}
					// Android 11+ needs resources.arsc stored uncompressed (apksig aligns it).
					ApkTagger.put(zout, e.getName(), data,
							e.getMethod() == ZipEntry.STORED || e.getName().equals("resources.arsc"));
				}
			}
			// Rokid's part, from this Station: its Java classes (behind our stand-ins) and library.
			try (ZipFile runtime = new ZipFile(rt.sourceDir)) {
				ZipEntry dex = runtime.getEntry("classes.dex");
				ZipEntry lib = runtime.getEntry("lib/arm64-v8a/libopenxr_rokid.so");
				if (dex == null)
					throw new Exception("Rokid's runtime has no classes.dex; this Rokid version isn't supported");
				ApkTagger.put(zout, "classes2.dex", read(runtime.getInputStream(dex)), false);
				byte[] so = lib != null ? read(runtime.getInputStream(lib))
						: readFile(new File(rt.nativeLibraryDir, "libopenxr_rokid.so"));
				ApkTagger.put(zout, "lib/arm64-v8a/libopenxr_rokid.so", so, false);
			}
		}
		ApkTagger.sign(unsigned, out, signer);
		unsigned.delete();
	}

	private static byte[] read(InputStream in) throws Exception {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[1 << 16];
		for (int n; (n = in.read(buf)) > 0; )
			out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static byte[] readFile(File f) throws Exception {
		if (!f.exists())
			throw new Exception("Rokid's OpenXR library wasn't found; this Rokid version isn't supported");
		try (InputStream in = new java.io.FileInputStream(f)) {
			return read(in);
		}
	}
}
