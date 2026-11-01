package io.github.wbrooks88.xrstore;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Edits a binary (compiled) AndroidManifest.xml:
 *  - adds <meta-data android:name="com.rokid.sdk" android:value="uxr"/> as the first child of
 *    <application> (the Station only lets apps with it draw on the glasses);
 *  - optionally adds <queries> for the Khronos OpenXR runtime broker, which the Khronos loader
 *    needs to reach on Android 11+ (Quest builds made for Meta's loader lack it);
 *  - optionally sets android:versionCode.
 *
 * Attribute names that carry a framework resource id (android:name 0x01010003,
 * android:value 0x01010024) must sit in the string pool's resource-mapped prefix, so a
 * missing one is inserted there and every string reference in the document is shifted.
 */
final class AxmlPatcher {
	static final String KEY = "com.rokid.sdk", VALUE = "uxr";
	private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
	private static final int ATTR_NAME = 0x01010003, ATTR_VALUE = 0x01010024, ATTR_AUTHORITIES = 0x01010018,
			ATTR_VERSION_CODE = 0x0101021b, ATTR_VERSION_NAME = 0x0101021c;
	private static final int TYPE_INT_DEC = 0x10;
	static final String BROKER_AUTHORITIES = "org.khronos.openxr.runtime_broker;org.khronos.openxr.system_runtime_broker";
	static final String RUNTIME_SERVICE = "org.khronos.openxr.OpenXRRuntimeService";

	static final class Options {
		boolean tag = true;
		boolean brokerQueries;
		int versionCode = -1;
		String versionName;
	}
	private static final int NONE = -1;
	private static final int RES_STRING_POOL = 0x0001, RES_XML = 0x0003, RES_XML_RESOURCE_MAP = 0x0180;
	private static final int XML_START_NS = 0x0100, XML_END_NS = 0x0101, XML_START_ELEMENT = 0x0102,
			XML_END_ELEMENT = 0x0103, XML_CDATA = 0x0104;
	private static final int TYPE_STRING = 0x03;
	private static final int UTF8_FLAG = 0x100;

	static final class AlreadyTaggedException extends Exception {}

	private final List<String> strings = new ArrayList<>();
	private int[] resMap = new int[0];
	private boolean utf8;
	private final List<byte[]> nodes = new ArrayList<>();

	/** Adds the tag; throws AlreadyTaggedException if it is already there. */
	static byte[] patch(byte[] axml) throws Exception {
		return new AxmlPatcher().run(axml, new Options());
	}

	/** Applies {@code o}; with o.tag set and the tag already present, it is simply kept. */
	static byte[] patch(byte[] axml, Options o) throws Exception {
		AxmlPatcher p = new AxmlPatcher();
		p.tolerateTag = true;
		return p.run(axml, o);
	}

	private boolean tolerateTag;

	private byte[] run(byte[] axml, Options o) throws Exception {
		ByteBuffer in = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
		if (in.getShort(0) != RES_XML)
			throw new Exception("not a binary XML file");
		int pos = in.getShort(2) & 0xffff;
		while (pos < axml.length) {
			int type = in.getShort(pos) & 0xffff, size = in.getInt(pos + 4);
			if (type == RES_STRING_POOL)
				readStringPool(in, pos);
			else if (type == RES_XML_RESOURCE_MAP) {
				resMap = new int[(size - 8) / 4];
				for (int i = 0; i < resMap.length; i++)
					resMap[i] = in.getInt(pos + 8 + 4 * i);
			} else
				nodes.add(Arrays.copyOfRange(axml, pos, pos + size));
			pos += size;
		}

		int nameAttr = mappedString("name", ATTR_NAME);
		int valueAttr = mappedString("value", ATTR_VALUE);
		int authoritiesAttr = o.brokerQueries ? mappedString("authorities", ATTR_AUTHORITIES) : NONE;
		int ns = strings.indexOf(ANDROID_NS);
		if (ns < 0)
			throw new Exception("manifest has no android namespace");
		boolean tagged = hasTag();
		if (tagged && o.tag && !tolerateTag)
			throw new AlreadyTaggedException();
		boolean addTag = o.tag && !tagged;
		boolean addQueries = o.brokerQueries && !strings.contains(BROKER_AUTHORITIES);
		int metaData = string("meta-data"), key = string(KEY), value = string(VALUE);

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		boolean seenApplication = false;
		for (byte[] node : nodes) {
			ByteBuffer b = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
			boolean isStart = (b.getShort(0) & 0xffff) == XML_START_ELEMENT;
			String element = isStart ? str(b.getInt(20)) : null;
			int line = b.getInt(8);
			if (isStart && "manifest".equals(element) && o.versionCode >= 0)
				setIntAttr(node, ATTR_VERSION_CODE, o.versionCode);
			if (isStart && "manifest".equals(element) && o.versionName != null)
				setStringAttr(node, ATTR_VERSION_NAME, string(o.versionName));
			if (isStart && "application".equals(element) && !seenApplication && addQueries)
				body.write(brokerQueries(line, ns, nameAttr, authoritiesAttr));
			body.write(node);
			if (isStart && "application".equals(element) && !seenApplication) {
				seenApplication = true;
				if (addTag) {
					body.write(startElement(line, metaData, ns, new int[][] {
							{nameAttr, key}, {valueAttr, value}}));
					body.write(endElement(line, metaData));
				}
			}
		}
		if (!seenApplication)
			throw new Exception("manifest has no <application>");

		byte[] pool = writeStringPool(), map = writeResMap(), nodeBytes = body.toByteArray();
		ByteBuffer out = ByteBuffer.allocate(8 + pool.length + map.length + nodeBytes.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		out.putShort((short) RES_XML).putShort((short) 8).putInt(out.capacity());
		out.put(pool).put(map).put(nodeBytes);
		return out.array();
	}

	/** <queries><provider android:authorities=…/><intent><action android:name=…/></intent></queries> */
	private byte[] brokerQueries(int line, int ns, int nameAttr, int authoritiesAttr) throws Exception {
		int queries = string("queries"), provider = string("provider"), intent = string("intent"),
				action = string("action"), authorities = string(BROKER_AUTHORITIES), service = string(RUNTIME_SERVICE);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.write(startElement(line, queries, ns, new int[0][]));
		b.write(startElement(line, provider, ns, new int[][] {{authoritiesAttr, authorities}}));
		b.write(endElement(line, provider));
		b.write(startElement(line, intent, ns, new int[0][]));
		b.write(startElement(line, action, ns, new int[][] {{nameAttr, service}}));
		b.write(endElement(line, action));
		b.write(endElement(line, intent));
		b.write(endElement(line, queries));
		return b.toByteArray();
	}

	/** Sets an integer attribute (by framework resource id) of a start-element node in place. */
	private void setIntAttr(byte[] node, int resId, int value) {
		ByteBuffer b = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
		int start = 16 + (b.getShort(24) & 0xffff), size = b.getShort(26) & 0xffff,
				count = b.getShort(28) & 0xffff;
		for (int i = 0; i < count; i++) {
			int a = start + i * size, name = b.getInt(a + 4);
			if (name >= 0 && name < resMap.length && resMap[name] == resId) {
				b.putInt(a + 8, NONE);
				node[a + 15] = (byte) TYPE_INT_DEC;
				b.putInt(a + 16, value);
			}
		}
	}

	/** Sets a string attribute (by framework resource id) of a start-element node in place. */
	private void setStringAttr(byte[] node, int resId, int stringIndex) {
		ByteBuffer b = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
		int start = 16 + (b.getShort(24) & 0xffff), size = b.getShort(26) & 0xffff,
				count = b.getShort(28) & 0xffff;
		for (int i = 0; i < count; i++) {
			int a = start + i * size, name = b.getInt(a + 4);
			if (name >= 0 && name < resMap.length && resMap[name] == resId) {
				b.putInt(a + 8, stringIndex);
				node[a + 15] = (byte) TYPE_STRING;
				b.putInt(a + 16, stringIndex);
			}
		}
	}

	private boolean hasTag() {
		for (byte[] node : nodes) {
			ByteBuffer b = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
			if ((b.getShort(0) & 0xffff) != XML_START_ELEMENT || !"meta-data".equals(str(b.getInt(20))))
				continue;
			int count = b.getShort(28) & 0xffff;
			for (int i = 0; i < count; i++)
				if (KEY.equals(str(b.getInt(36 + 20 * i + 8))))
					return true;
		}
		return false;
	}

	private String str(int index) {
		return index >= 0 && index < strings.size() ? strings.get(index) : null;
	}

	/** Index of a string, appended to the end of the pool if missing. */
	private int string(String s) {
		int i = strings.indexOf(s);
		if (i >= 0)
			return i;
		strings.add(s);
		return strings.size() - 1;
	}

	/** Index of an attribute-name string mapped to resource id {@code res}, inserting one if needed. */
	private int mappedString(String s, int res) {
		for (int i = 0; i < resMap.length; i++)
			if (resMap[i] == res)
				return i;
		int at = resMap.length;
		strings.add(at, s);
		resMap = Arrays.copyOf(resMap, at + 1);
		resMap[at] = res;
		for (byte[] node : nodes)
			shiftRefs(node, at);
		return at;
	}

	/** Bumps every string reference >= {@code from} in a node chunk by one. */
	private static void shiftRefs(byte[] node, int from) {
		ByteBuffer b = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
		int type = b.getShort(0) & 0xffff;
		List<Integer> refs = new ArrayList<>();
		refs.add(12); // comment
		switch (type) {
		case XML_START_NS: case XML_END_NS: case XML_END_ELEMENT:
			refs.add(16); refs.add(20);
			break;
		case XML_CDATA:
			refs.add(16);
			if (node[16 + 4 + 3] == TYPE_STRING)
				refs.add(16 + 8);
			break;
		case XML_START_ELEMENT: {
			refs.add(16); refs.add(20);
			int start = 16 + (b.getShort(24) & 0xffff), size = b.getShort(26) & 0xffff,
					count = b.getShort(28) & 0xffff;
			for (int i = 0; i < count; i++) {
				int a = start + i * size;
				refs.add(a); refs.add(a + 4); refs.add(a + 8);
				if (node[a + 15] == TYPE_STRING)
					refs.add(a + 16);
			}
			break;
		}
		default:
			return;
		}
		for (int off : refs) {
			int v = b.getInt(off);
			if (v != NONE && v >= from)
				b.putInt(off, v + 1);
		}
	}

	private static byte[] startElement(int line, int name, int ns, int[][] attrs) {
		ByteBuffer b = ByteBuffer.allocate(36 + 20 * attrs.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) XML_START_ELEMENT).putShort((short) 16).putInt(b.capacity());
		b.putInt(line).putInt(NONE);
		b.putInt(NONE).putInt(name);
		b.putShort((short) 20).putShort((short) 20).putShort((short) attrs.length);
		b.putShort((short) 0).putShort((short) 0).putShort((short) 0);
		for (int[] a : attrs) {
			b.putInt(ns).putInt(a[0]).putInt(a[1]);
			b.putShort((short) 8).put((byte) 0).put((byte) TYPE_STRING).putInt(a[1]);
		}
		return b.array();
	}

	private static byte[] endElement(int line, int name) {
		ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) XML_END_ELEMENT).putShort((short) 16).putInt(24);
		b.putInt(line).putInt(NONE).putInt(NONE).putInt(name);
		return b.array();
	}

	private void readStringPool(ByteBuffer b, int pos) throws Exception {
		int count = b.getInt(pos + 8), styles = b.getInt(pos + 12), flags = b.getInt(pos + 16);
		int stringsStart = b.getInt(pos + 20);
		if (styles != 0)
			throw new Exception("styled strings in manifest are not supported");
		utf8 = (flags & UTF8_FLAG) != 0;
		int headerSize = b.getShort(pos + 2) & 0xffff;
		for (int i = 0; i < count; i++) {
			int p = pos + stringsStart + b.getInt(pos + headerSize + 4 * i);
			if (utf8) {
				p += lengthSize8(b, p); // UTF-16 length
				int len = b.get(p) & 0xff;
				int n = 1;
				if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(p + 1) & 0xff); n = 2; }
				byte[] s = new byte[len];
				b.position(p + n); b.get(s);
				strings.add(new String(s, StandardCharsets.UTF_8));
			} else {
				int len = b.getShort(p) & 0xffff, n = 2;
				if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(p + 2) & 0xffff); n = 4; }
				byte[] s = new byte[len * 2];
				b.position(p + n); b.get(s);
				strings.add(new String(s, StandardCharsets.UTF_16LE));
			}
		}
	}

	private static int lengthSize8(ByteBuffer b, int p) {
		return (b.get(p) & 0x80) != 0 ? 2 : 1;
	}

	private byte[] writeStringPool() {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int[] offsets = new int[strings.size()];
		for (int i = 0; i < offsets.length; i++) {
			offsets[i] = data.size();
			String s = strings.get(i);
			if (utf8) {
				byte[] u = s.getBytes(StandardCharsets.UTF_8);
				writeLen8(data, s.length());
				writeLen8(data, u.length);
				data.write(u, 0, u.length);
				data.write(0);
			} else {
				byte[] u = s.getBytes(StandardCharsets.UTF_16LE);
				int len = s.length();
				if (len > 0x7fff) {
					int hi = 0x8000 | (len >> 16);
					data.write(hi & 0xff); data.write(hi >> 8);
				}
				data.write(len & 0xff); data.write((len >> 8) & 0xff);
				data.write(u, 0, u.length);
				data.write(0); data.write(0);
			}
		}
		while (data.size() % 4 != 0)
			data.write(0);
		int headerSize = 28, stringsStart = headerSize + 4 * offsets.length;
		ByteBuffer b = ByteBuffer.allocate(stringsStart + data.size()).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) RES_STRING_POOL).putShort((short) headerSize).putInt(b.capacity());
		b.putInt(offsets.length).putInt(0).putInt(utf8 ? UTF8_FLAG : 0).putInt(stringsStart).putInt(0);
		for (int o : offsets)
			b.putInt(o);
		b.put(data.toByteArray());
		return b.array();
	}

	private static void writeLen8(ByteArrayOutputStream out, int len) {
		if (len > 0x7f)
			out.write(0x80 | (len >> 8));
		out.write(len & 0xff);
	}

	private byte[] writeResMap() {
		ByteBuffer b = ByteBuffer.allocate(8 + 4 * resMap.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) RES_XML_RESOURCE_MAP).putShort((short) 8).putInt(b.capacity());
		for (int r : resMap)
			b.putInt(r);
		return b.array();
	}
}
