package io.github.wbrooks88.xrstore;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** The store's catalogue on disk (filesDir/store.tsv), shared by the store and RefreshJob. */
final class StoreData {
	private StoreData() {}

	private static File file(Context c) {
		return new File(c.getFilesDir(), "store.tsv");
	}

	/** When the catalogue was last refreshed, in ms since the epoch, or 0. */
	static long updatedAt(Context c) {
		return file(c).lastModified();
	}

	static synchronized List<WebXR.Site> read(Context c) {
		List<WebXR.Site> out = new ArrayList<>();
		try (FileInputStream in = new FileInputStream(file(c))) {
			String text = new String(ApkTagger.read(in), "UTF-8");
			for (String line : text.split("\n")) {
				String[] f = line.split("\t", -1);
				if (f.length == 6)
					out.add(new WebXR.Site(f[0], f[1], f[2], f[3], f[4], f[5]));
			}
		} catch (Exception ignored) {
			// no catalogue yet
		}
		return out;
	}

	static synchronized void write(Context c, List<WebXR.Site> sites) {
		StringBuilder b = new StringBuilder();
		for (WebXR.Site s : sites)
			b.append(clean(s.name)).append('\t').append(clean(s.url)).append('\t').append(clean(s.about))
					.append('\t').append(clean(s.source)).append('\t').append(clean(s.category))
					.append('\t').append(clean(s.image)).append('\n');
		File tmp = new File(file(c).getPath() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp)) {
			out.write(b.toString().getBytes("UTF-8"));
		} catch (Exception e) {
			return;
		}
		tmp.renameTo(file(c));
	}

	/** Fetches all sources (keeping a failed source's last entries) and saves the result. */
	static List<WebXR.Site> refresh(Context c, WebXR.Progress progress) {
		List<WebXR.Site> fresh = WebXR.fetchAll(progress, read(c));
		if (!fresh.isEmpty())
			write(c, fresh);
		return fresh;
	}

	private static String clean(String s) {
		return s.replace('\t', ' ').replace('\n', ' ');
	}
}
