package io.github.wbrooks88.xrstore;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;

import javax.security.auth.x500.X500Principal;

/**
 * The key XR Store signs apps with on this Station (tagged apps and the OpenXR bridge). It is
 * generated on first use in the Android keystore and never leaves the device, so no signing
 * key is shared between people. Apps it signs can only be updated by XR Store on this device.
 */
final class DeviceKey {
	private static final String ALIAS = "xrstore-signing";

	private DeviceKey() {}

	static synchronized ApkTagger.Signer get() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		if (!ks.containsAlias(ALIAS)) {
			Calendar until = Calendar.getInstance();
			until.add(Calendar.YEAR, 30);
			KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
			g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
					.setKeySize(2048)
					.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
					.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
					.setCertificateSubject(new X500Principal("CN=XR Store on this device"))
					.setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
					.setCertificateNotAfter(until.getTime())
					.build());
			g.generateKeyPair();
		}
		PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
		X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
		return new ApkTagger.Signer(key, cert);
	}
}
