package io.github.wbrooks88.xrstore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Curated OpenXR apps (assets/catalog.json), each fetched from the latest GitHub release
 * of its project, so the catalog never pins a stale version.
 */
final class Catalog {
	interface Progress {
		void update(String text);
	}

	static final class Entry {
		String name, publisher, about, repo, asset, zipEntry, status;
	}

	static List<Entry> load(InputStream json) throws Exception {
		JSONArray array = new JSONArray(new String(ApkTagger.read(json), "UTF-8"));
		List<Entry> entries = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject o = array.getJSONObject(i);
			Entry e = new Entry();
			e.name = o.getString("name");
			e.publisher = o.getString("publisher");
			e.about = o.optString("about");
			e.repo = o.getString("repo");
			e.asset = o.getString("asset");
			e.zipEntry = o.optString("zipEntry", null);
			e.status = o.optString("status");
			entries.add(e);
		}
		return entries;
	}

	/** Downloads the entry's APK from the latest release into {@code dest}. */
	static void download(Entry e, File dest, File work, Progress progress) throws Exception {
		progress.update("Looking up the latest " + e.name + " release…");
		JSONObject release = new JSONObject(new String(ApkTagger.read(
				open("https://api.github.com/repos/" + e.repo + "/releases/latest")), "UTF-8"));
		JSONArray assets = release.getJSONArray("assets");
		Pattern want = Pattern.compile(e.asset);
		JSONObject asset = null;
		for (int i = 0; i < assets.length() && asset == null; i++)
			if (want.matcher(assets.getJSONObject(i).getString("name")).find())
				asset = assets.getJSONObject(i);
		if (asset == null)
			throw new Exception(e.repo + " " + release.optString("tag_name") + " has no matching download");

		String label = e.name + " " + release.optString("tag_name");
		File file = e.zipEntry == null ? dest : new File(work, "download.zip");
		fetch(asset.getString("browser_download_url"), file, asset.optLong("size"), label, progress);
		if (e.zipEntry == null)
			return;

		progress.update("Unpacking " + label + "…");
		Pattern inner = Pattern.compile(e.zipEntry);
		try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(file))) {
			for (ZipEntry z; (z = zip.getNextEntry()) != null; ) {
				if (z.isDirectory() || !inner.matcher(z.getName()).find())
					continue;
				copy(zip, new FileOutputStream(dest), -1, null, null);
				file.delete();
				return;
			}
		}
		throw new Exception("no APK inside " + asset.getString("name"));
	}

	/** Downloads the newest release asset matching {@code assetRegex} of a GitLab project. */
	static void downloadGitLab(String host, String project, String assetRegex, File dest, Progress progress)
			throws Exception {
		progress.update("Looking up the latest release of " + project + "…");
		JSONArray releases = new JSONArray(new String(ApkTagger.read(open("https://" + host + "/api/v4/projects/"
				+ java.net.URLEncoder.encode(project, "UTF-8") + "/releases")), "UTF-8"));
		Pattern want = Pattern.compile(assetRegex);
		for (int r = 0; r < releases.length(); r++) {
			JSONObject release = releases.getJSONObject(r);
			JSONArray links = release.getJSONObject("assets").getJSONArray("links");
			for (int i = 0; i < links.length(); i++) {
				JSONObject link = links.getJSONObject(i);
				if (want.matcher(link.getString("name")).find()) {
					String url = link.optString("direct_asset_url", link.getString("url"));
					fetch(url, dest, -1, release.optString("tag_name", "release"), progress);
					return;
				}
			}
		}
		throw new Exception(project + " has no release with a matching download");
	}

	private static InputStream open(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("Accept", "application/vnd.github+json");
		c.setRequestProperty("User-Agent", "XRInstaller");
		c.setConnectTimeout(15000);
		c.setReadTimeout(30000);
		if (c.getResponseCode() != 200)
			throw new Exception(url + ": HTTP " + c.getResponseCode());
		return c.getInputStream();
	}

	private static void fetch(String url, File dest, long size, String label, Progress progress) throws Exception {
		try (InputStream in = open(url)) {
			copy(in, new FileOutputStream(dest), size, label, progress);
		}
	}

	private static void copy(InputStream in, OutputStream outStream, long size, String label, Progress progress)
			throws Exception {
		try (OutputStream out = outStream) {
			byte[] buf = new byte[1 << 16];
			long done = 0, shown = -1;
			for (int n; (n = in.read(buf)) > 0; ) {
				out.write(buf, 0, n);
				done += n;
				long mb = done >> 20;
				if (progress != null && mb != shown) {
					shown = mb;
					progress.update("Downloading " + label + ": " + mb + " MB"
							+ (size > 0 ? " of " + (size >> 20) + " MB" : "") + "…");
				}
			}
		}
	}
}
