import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.List;

// Check on the Station with app_process: CatalogTest catalog.json <index> <dir>
public class CatalogTest {
	public static void main(String[] a) throws Exception {
		Class<?> c = Class.forName("io.github.wbrooks88.xrstore.Catalog");
		Method load = c.getDeclaredMethod("load", java.io.InputStream.class);
		load.setAccessible(true);
		List<?> entries = (List<?>) load.invoke(null, new FileInputStream(a[0]));
		Object entry = entries.get(Integer.parseInt(a[1]));
		Class<?> progress = Class.forName("io.github.wbrooks88.xrstore.Catalog$Progress");
		Object p = java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[] {progress},
				(proxy, m, args) -> { System.out.println(args[0]); return null; });
		Method dl = c.getDeclaredMethod("download", entry.getClass(), File.class, File.class, progress);
		dl.setAccessible(true);
		File dir = new File(a[2]);
		dl.invoke(null, entry, new File(dir, "got.apk"), dir, p);
		System.out.println("downloaded " + new File(dir, "got.apk").length());
	}
}
