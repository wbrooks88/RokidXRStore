package io.github.wbrooks88.xrstore;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites an APK so it runs on the Station, and signs it:
 *  - the Rokid "uxr" tag in the manifest (AxmlPatcher);
 *  - if it carries an OpenXR loader other than Khronos's (Quest builds ship Meta's, which only
 *    finds Meta's runtime), that loader is replaced with the Khronos one and the manifest gets
 *    <queries> for the Khronos runtime broker.
 * Same job as tools/make-uxr.sh on a computer.
 */
final class ApkTagger {
	static final String LOADER = "lib/arm64-v8a/libopenxr_loader.so";

	static final class Signer {
		final PrivateKey key;
		final X509Certificate cert;

		Signer(PrivateKey key, X509Certificate cert) {
			this.key = key;
			this.cert = cert;
		}
	}

	interface Log {
		void say(String text);
	}

	/**
	 * Tags {@code in} into {@code out}. {@code khronosLoader} replaces a non-Khronos OpenXR loader
	 * (null leaves loaders alone). Throws AxmlPatcher.AlreadyTaggedException when the APK needs
	 * no change at all.
	 */
	static void tag(File in, File out, File work, Signer signer, byte[] khronosLoader, Log log) throws Exception {
		File unsigned = new File(work, "unsigned.apk");
		boolean changed = false;
		try (ZipFile zip = new ZipFile(in)) {
			ZipEntry loader = zip.getEntry(LOADER);
			boolean swapLoader = khronosLoader != null && loader != null
					&& !contains(read(zip.getInputStream(loader)), "org.khronos.openxr.runtime_broker");
			if (swapLoader)
				log.say("Replacing Meta's OpenXR loader with the Khronos loader…");
			AxmlPatcher.Options options = new AxmlPatcher.Options();
			options.brokerQueries = swapLoader;
			byte[] manifest = read(zip.getInputStream(zip.getEntry("AndroidManifest.xml")));
			byte[] patched = AxmlPatcher.patch(manifest, options);
			changed = swapLoader || !java.util.Arrays.equals(manifest, patched);
			if (!changed)
				throw new AxmlPatcher.AlreadyTaggedException();
			try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(unsigned))) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();
					String name = e.getName();
					if (isSignatureFile(name))
						continue;
					byte[] data = name.equals("AndroidManifest.xml") ? patched
							: swapLoader && name.equals(LOADER) ? khronosLoader
							: read(zip.getInputStream(e));
					put(zout, name, data, e.getMethod() == ZipEntry.STORED);
				}
			}
		}
		sign(unsigned, out, signer);
		unsigned.delete();
	}

	static void put(ZipOutputStream zout, String name, byte[] data, boolean stored) throws Exception {
		ZipEntry o = new ZipEntry(name);
		if (stored) {
			CRC32 crc = new CRC32();
			crc.update(data);
			o.setMethod(ZipEntry.STORED);
			o.setSize(data.length);
			o.setCompressedSize(data.length);
			o.setCrc(crc.getValue());
		} else {
			o.setMethod(ZipEntry.DEFLATED);
		}
		zout.putNextEntry(o);
		zout.write(data);
		zout.closeEntry();
	}

	static void sign(File unsigned, File out, Signer signer) throws Exception {
		ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
				"CERT", signer.key, Collections.singletonList(signer.cert)).build();
		new ApkSigner.Builder(Collections.singletonList(config))
				.setInputApk(unsigned)
				.setOutputApk(out)
				.setV1SigningEnabled(true)
				.setV2SigningEnabled(true)
				.setV3SigningEnabled(false)
				.setLibraryPageAlignmentBytes(16384)
				.build()
				.sign();
	}

	private static boolean isSignatureFile(String name) {
		if (!name.startsWith("META-INF/"))
			return false;
		String n = name.substring(9).toUpperCase();
		return n.equals("MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
				|| n.endsWith(".DSA") || n.endsWith(".EC");
	}

	private static boolean contains(byte[] haystack, String needle) {
		byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		outer:
		for (int i = 0; i + n.length <= haystack.length; i++) {
			for (int j = 0; j < n.length; j++)
				if (haystack[i + j] != n[j])
					continue outer;
			return true;
		}
		return false;
	}

	static byte[] read(InputStream in) throws Exception {
		try (InputStream s = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			for (int n; (n = s.read(buf)) > 0; )
				out.write(buf, 0, n);
			return out.toByteArray();
		}
	}
}
