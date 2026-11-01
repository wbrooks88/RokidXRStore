// Host check: prints what the WebXR store would list.
public class WebXRTest {
	static Object field(Object o, String name) throws Exception {
		java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	public static void main(String[] a) throws Exception {
		Class<?> c = Class.forName("io.github.wbrooks88.xrstore.WebXR");
		Class<?> p = Class.forName("io.github.wbrooks88.xrstore.WebXR$Progress");
		Object progress = java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[] {p},
				(proxy, m, args) -> { System.out.println("# " + args[0]); return null; });
		java.lang.reflect.Method f = c.getDeclaredMethod("fetchAll", p);
		f.setAccessible(true);
		for (Object s : (java.util.List<?>) f.invoke(null, progress)) {
			System.out.println(field(s, "category") + " | " + field(s, "name") + " | " + field(s, "image"));
		}
	}
}
