package io.github.wbrooks88.xrstore;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebXR experiences gathered live from public, curated sources. Each opens in Wolvic,
 * so nothing is installed.
 *   Wolvic start page: Igalia's own picks (wolvic.com/en/start/)
 *   heyVR: its game sitemap (heyvr.io/sitemap/games.xml)
 *   dmnshd.gg: its games page
 *   Awesome WebXR (CC0 list on GitHub): the experience sections of its README
 *   itch.io: browser (HTML5) games tagged webxr or virtual-reality, from itch's public
 *     browse listings (no downloads; the game page has a Run button)
 *   three.js: the WebXR examples (threejs.org/examples/files.json)
 */
final class WebXR {
	static final class Site {
		final String name, url, about, source, category, image;

		Site(String name, String url, String about, String source) {
			this(name, url, about, source, source, "");
		}

		Site(String name, String url, String about, String source, String category, String image) {
			this.name = name;
			this.url = url;
			this.about = about;
			this.source = source;
			this.category = category;
			this.image = image == null ? "" : image;
		}
	}

	interface Progress {
		void update(String text);
	}

	/** Sections of the Awesome WebXR README that list experiences rather than tools or communities. */
	private static final String[] AWESOME_SECTIONS = {
		"Standout Projects", "Art", "Audio", "Avatars", "Content Hubs", "Social Spaces",
	};

	interface Source {
		List<Site> fetch() throws Exception;
	}

	static final String[] SOURCE_NAMES = {"Wolvic picks", "Awesome WebXR", "heyVR", "dmnshd.gg", "itch.io", "three.js"};

	/**
	 * Fetches every source. A source that fails, or comes back empty (its page changed and
	 * nothing parsed), keeps its entries from {@code previous} and is named in the summary,
	 * so a broken source never empties its rows.
	 */
	static List<Site> fetchAll(Progress progress, List<Site> previous) {
		Map<String, Site> byUrl = new LinkedHashMap<>();
		List<String> failed = new ArrayList<>();
		Source[] sources = {WebXR::wolvicStart, WebXR::awesome, WebXR::heyVR, WebXR::dmnshd,
				WebXR::itch, WebXR::threejs};
		for (int i = 0; i < sources.length; i++) {
			List<Site> got;
			try {
				progress.update("Updating " + SOURCE_NAMES[i] + "… (" + byUrl.size() + " so far)");
				got = sources[i].fetch();
			} catch (Exception e) {
				got = new ArrayList<>();
			}
			if (got.isEmpty()) {
				failed.add(SOURCE_NAMES[i]);
				for (Site s : previous)
					if (s.source.startsWith(SOURCE_NAMES[i]))
						got.add(s);
			}
			for (Site s : got)
				byUrl.putIfAbsent(s.category + "|" + key(s.url), s); // a site may sit in several rows
		}
		java.util.Set<String> distinct = new java.util.HashSet<>();
		for (Site site : byUrl.values())
			distinct.add(key(site.url));
		progress.update(distinct.size() + " WebXR experiences"
				+ (failed.isEmpty() ? "" : " (couldn't update " + String.join(", ", failed) + "; showing its last list)"));
		return new ArrayList<>(byUrl.values());
	}

	static List<Site> fetchAll(Progress progress) {
		return fetchAll(progress, new ArrayList<>());
	}

	private static String key(String url) {
		return url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/$", "");
	}

	static List<Site> awesome() throws Exception {
		String md = get("https://raw.githubusercontent.com/msub2/awesome-webxr/main/README.md");
		List<Site> sites = new ArrayList<>();
		Pattern item = Pattern.compile("^- \\[([^\\]]+)\\]\\((https?://[^)]+)\\)\\s*-?\\s*(.*)$");
		String section = null;
		for (String line : md.split("\n")) {
			if (line.startsWith("## ")) {
				section = null;
				for (String s : AWESOME_SECTIONS)
					if (line.substring(3).trim().equals(s))
						section = s;
				continue;
			}
			Matcher m = item.matcher(line);
			if (section != null && m.matches())
				sites.add(new Site(m.group(1), m.group(2), stripLinks(m.group(3)), "Awesome WebXR · " + section,
						"Community picks", ""));
		}
		return sites;
	}

	static List<Site> heyVR() throws Exception {
		String xml = get("https://heyvr.io/sitemap/games.xml");
		List<Site> sites = new ArrayList<>();
		Matcher m = Pattern.compile("<loc>(https://heyvr\\.io/arcade/games/([a-z0-9-]+))</loc>").matcher(xml);
		while (m.find())
			sites.add(new Site(titleCase(m.group(2)), m.group(1), "", "heyVR", "heyVR arcade", ""));
		return sites;
	}

	private static final Pattern DMNSHD_IMG = Pattern.compile("<img[^>]*src=\"(https://cdn\\.dmnshd\\.gg/[^\"]+)\"");

	static List<Site> dmnshd() throws Exception {
		String html = get("https://dmnshd.gg/games");
		List<Site> sites = new ArrayList<>();
		Matcher m = Pattern.compile("href=\"/games/([a-z0-9-]+)\"").matcher(html);
		Pattern alt = Pattern.compile("alt=\"([^\"]+)\"");
		List<String> seen = new ArrayList<>();
		while (m.find()) {
			String slug = m.group(1);
			if (seen.contains(slug))
				continue;
			seen.add(slug);
			Matcher a = alt.matcher(html), img = DMNSHD_IMG.matcher(html);
			String name = a.find(m.end()) ? a.group(1) : titleCase(slug);
			String image = img.find(m.end()) ? img.group(1) : "";
			sites.add(new Site(name, "https://dmnshd.gg/games/" + slug, "", "dmnshd.gg", "dmnshd arcade", image));
		}
		return sites;
	}

	/** Wolvic's start page: sections of picks, each with a title, a line and a cover image. */
	static List<Site> wolvicStart() throws Exception {
		String html = get("https://wolvic.com/en/start/");
		List<Site> sites = new ArrayList<>();
		Matcher m = Pattern.compile("<h[123][^>]*>(.*?)</h[123]>|<a class=\"item[^\"]*\" href=\"([^\"]+)\">(.*?)</a>",
				Pattern.DOTALL).matcher(html);
		String section = "";
		while (m.find()) {
			if (m.group(1) != null) {
				section = m.group(1).replaceAll("<[^>]+>", "").trim();
				continue;
			}
			String category = wolvicCategory(section);
			if (category == null)
				continue;
			String item = m.group(3);
			Matcher title = Pattern.compile("<h4>([^<]*)</h4>").matcher(item);
			Matcher about = Pattern.compile("<p>([^<]*)</p>").matcher(item);
			Matcher image = Pattern.compile("<img (?!class=\"play_vr)[^>]*src=\"([^\"]+)\"").matcher(item);
			if (!title.find())
				continue;
			sites.add(new Site(unescape(title.group(1)), m.group(2), about.find() ? unescape(about.group(1)) : "",
					"Wolvic picks", category, image.find() ? absolute("https://wolvic.com", image.group(1)) : ""));
		}
		return sites;
	}

	private static String absolute(String origin, String src) {
		return src.startsWith("http") ? src : origin + (src.startsWith("/") ? "" : "/") + src;
	}

	/** Store-front row for a Wolvic start-page section, or null to leave it out. */
	private static String wolvicCategory(String section) {
		switch (section) {
		case "Featured": return "Featured";
		case "Games": return "Games";
		case "Experiences": return "Experiences";
		case "Painting": return "Create";
		case "Videos": return "360° videos";
		case "heyVR.io": return "heyVR arcade";
		default: return null; // Explore (empty), Streaming (ordinary 2D sites)
		}
	}

	private static final Pattern IMG = Pattern.compile("data-lazy_src=\"([^\"]+)\"");
	private static final Pattern GENRE = Pattern.compile("class=\"game_genre\">([^<]+)<");

	/** itch.io browser games tagged for VR, from the public browse listings (JSON pages). */
	static List<Site> itch() throws Exception {
		List<Site> sites = new ArrayList<>();
		Pattern title = Pattern.compile("class=\"game_title\"><a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)<");
		Pattern text = Pattern.compile("class=\"game_text\"[^>]*>([^<]*)<");
		Pattern author = Pattern.compile("class=\"game_author\"><a[^>]*>([^<]+)<");
		for (String tag : new String[] {"webxr", "virtual-reality"}) {
			for (int page = 1; page <= 15; page++) {
				org.json.JSONObject d = new org.json.JSONObject(
						get("https://itch.io/games/html5/tag-" + tag + "?format=json&page=" + page));
				if (d.optInt("num_items") == 0)
					break;
				for (String cell : d.getString("content").split("<div class=\"game_cell")) {
					Matcher t = title.matcher(cell);
					if (!t.find())
						continue;
					Matcher x = text.matcher(cell), a = author.matcher(cell);
					String about = (x.find() ? unescape(x.group(1)) : "")
							+ (a.find() ? (" · by " + unescape(a.group(1))) : "");
					Matcher img = IMG.matcher(cell), genre = GENRE.matcher(cell);
					String g = genre.find() ? unescape(genre.group(1)) : "";
					sites.add(new Site(unescape(t.group(2)), t.group(1), about.replaceFirst("^ · ", ""),
							"itch.io (press Run game)", "itch.io: " + (g.isEmpty() ? "More games" : g),
							img.find() ? img.group(1) : ""));
				}
				Thread.sleep(500); // be gentle with itch.io
			}
		}
		return sites;
	}

	static List<Site> threejs() throws Exception {
		org.json.JSONArray examples = new org.json.JSONObject(get("https://threejs.org/examples/files.json"))
				.getJSONArray("webxr");
		List<Site> sites = new ArrayList<>();
		for (int i = 0; i < examples.length(); i++) {
			String name = examples.getString(i);
			sites.add(new Site(titleCase(name.replace("webxr_", "").replace('_', '-')),
					"https://threejs.org/examples/" + name + ".html", "three.js WebXR example", "three.js",
					"Tech demos", "https://threejs.org/examples/screenshots/" + name + ".jpg"));
		}
		return sites;
	}

	private static String unescape(String html) {
		return html.replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&amp;", "&").trim();
	}

	private static String stripLinks(String md) {
		return md.replaceAll("\\[\\[?([^\\]]+)\\]?\\]\\([^)]*\\)", "$1").trim();
	}

	private static String titleCase(String slug) {
		StringBuilder b = new StringBuilder();
		for (String w : slug.split("-")) {
			if (w.isEmpty())
				continue;
			if (b.length() > 0)
				b.append(' ');
			b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return b.toString();
	}

	private static String get(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("User-Agent", "XRInstaller (Rokid Station 2)");
		c.setConnectTimeout(15000);
		c.setReadTimeout(30000);
		if (c.getResponseCode() != 200)
			throw new Exception(url + ": HTTP " + c.getResponseCode());
		try (InputStream in = c.getInputStream()) {
			return new String(ApkTagger.read(in), "UTF-8");
		}
	}
}
