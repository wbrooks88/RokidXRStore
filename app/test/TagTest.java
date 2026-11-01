import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

// Check: TagTest in.apk out.apk key.pk8 cert.der [khronos-loader.so] (host java, or app_process on the Station)
public class TagTest {
	public static void main(String[] a) throws Exception {
		PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(new File(a[2]).toPath())));
		X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(a[3]));
		Class<?> tagger = Class.forName("io.github.wbrooks88.xrstore.ApkTagger");
		Class<?> signerClass = Class.forName("io.github.wbrooks88.xrstore.ApkTagger$Signer");
		Class<?> logClass = Class.forName("io.github.wbrooks88.xrstore.ApkTagger$Log");
		Constructor<?> ctor = signerClass.getDeclaredConstructor(PrivateKey.class, X509Certificate.class);
		ctor.setAccessible(true);
		Object signer = ctor.newInstance(key, cert);
		Object log = java.lang.reflect.Proxy.newProxyInstance(tagger.getClassLoader(), new Class<?>[] {logClass},
				(p, m, args) -> { System.out.println(args[0]); return null; });
		byte[] loader = a.length > 4 ? Files.readAllBytes(new File(a[4]).toPath()) : null;
		Method tag = tagger.getDeclaredMethod("tag", File.class, File.class, File.class, signerClass, byte[].class, logClass);
		tag.setAccessible(true);
		File out = new File(a[1]).getAbsoluteFile();
		tag.invoke(null, new File(a[0]), out, out.getParentFile(), signer, loader, log);
		System.out.println("tagged " + out);
	}
}
