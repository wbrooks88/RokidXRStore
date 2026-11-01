package io.github.wbrooks88.xrstore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads cover images into ImageViews: memory cache, then a disk cache, then the network. */
final class ImageLoader {
	private static final int WIDTH = 480; // decoded width; cards are smaller than this

	private final File dir;
	private final ExecutorService pool = Executors.newFixedThreadPool(4);
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
		@Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
	};

	ImageLoader(File cacheDir) {
		dir = new File(cacheDir, "covers");
		dir.mkdirs();
	}

	void load(String url, ImageView view) {
		view.setTag(url);
		Bitmap cached = memory.get(url);
		if (cached != null) {
			view.setImageBitmap(cached);
			return;
		}
		pool.execute(() -> {
			Bitmap b = fetch(url);
			if (b == null)
				return;
			memory.put(url, b);
			main.post(() -> {
				if (url.equals(view.getTag()))
					view.setImageBitmap(b);
			});
		});
	}

	/** Downloads an image into the disk cache (on the calling thread) without decoding it. */
	void prefetch(String url) {
		download(url);
	}

	private File download(String url) {
		File file = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
		if (file.exists())
			return file;
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setRequestProperty("User-Agent", "XRStore (Rokid Station 2)");
			c.setConnectTimeout(15000);
			c.setReadTimeout(20000);
			if (c.getResponseCode() != 200)
				return null;
			File tmp = new File(dir, file.getName() + ".part");
			try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
				byte[] buf = new byte[1 << 15];
				for (int n; (n = in.read(buf)) > 0; )
					out.write(buf, 0, n);
			}
			tmp.renameTo(file);
			return file;
		} catch (Exception e) {
			return null;
		}
	}

	private Bitmap fetch(String url) {
		File file = download(url);
		if (file == null)
			return null;
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file.getPath(), bounds);
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = 1;
		while (bounds.outWidth / (opts.inSampleSize * 2) >= WIDTH)
			opts.inSampleSize *= 2;
		return BitmapFactory.decodeFile(file.getPath(), opts);
	}
}
