package io.github.wbrooks88.xrstore;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The store front: rows of cover cards by category that you scroll through, like an app
 * store. The first row holds apps to install (assets/catalog.json); the rest are WebXR
 * experiences (WebXR, stored by StoreData), which open in Wolvic. Search is optional. The
 * catalogue refreshes daily in the background (RefreshJob), when stale on opening, or on request.
 */
final class StorePane {
	static final String WOLVIC = "com.igalia.wolvic.vanilla";
	private static final int PER_ROW = 24;
	/** Rows in this order first; itch.io genres follow by size, then the rest. */
	private static final String[] ROW_ORDER = {
		"Featured", "Games", "Experiences", "heyVR arcade", "dmnshd arcade",
	};
	private static final String[] ROW_LAST = {"Create", "360° videos", "Community picks", "Tech demos"};

	final LinearLayout view;
	private final MainActivity activity;
	private final ImageLoader images;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LinearLayout rows;
	private final EditText search;
	private final List<Catalog.Entry> apps;
	private List<WebXR.Site> sites = new ArrayList<>();
	private String expanded; // category shown in full, or null
	private boolean loading, loaded;

	StorePane(MainActivity activity, List<Catalog.Entry> apps) {
		this.activity = activity;
		this.apps = apps;
		images = new ImageLoader(activity.getCacheDir());
		view = new LinearLayout(activity);
		view.setOrientation(LinearLayout.VERTICAL);

		search = new EditText(activity);
		search.setHint("Search (optional)");
		search.setSingleLine();
		search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
			@Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
			@Override public void afterTextChanged(Editable s) { render(); }
		});
		view.addView(search);

		rows = new LinearLayout(activity);
		rows.setOrientation(LinearLayout.VERTICAL);
		view.addView(rows);
	}

	/** Opening the store refreshes the catalogue only if it is older than this. */
	private static final long STALE_MS = 6 * 60 * 60 * 1000L;

	void show() {
		RefreshJob.schedule(activity);
		if (!loaded) {
			sites = StoreData.read(activity);
			loaded = true;
			render();
		}
		if (sites.isEmpty() || System.currentTimeMillis() - StoreData.updatedAt(activity) > STALE_MS)
			refresh();
	}

	private void refresh() {
		if (loading)
			return;
		loading = true;
		render();
		new Thread(() -> {
			List<WebXR.Site> fresh = StoreData.refresh(activity, activity::say);
			main.post(() -> {
				loading = false;
				if (!fresh.isEmpty())
					sites = fresh;
				render();
			});
		}).start();
	}

	private View updatedLine() {
		LinearLayout line = new LinearLayout(activity);
		line.setGravity(Gravity.CENTER_VERTICAL);
		TextView when = new TextView(activity);
		long at = StoreData.updatedAt(activity);
		when.setText(loading ? "Updating the catalogue…"
				: at == 0 ? "Not updated yet"
				: "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(at)
						+ " · refreshes daily");
		when.setTextSize(14);
		when.setAlpha(0.7f);
		line.addView(when, new LinearLayout.LayoutParams(0, -2, 1));
		if (!loading)
			line.addView(button("Refresh now", v -> refresh()));
		return line;
	}

	// --- layout ---

	private int dp(int v) {
		return Math.round(v * activity.getResources().getDisplayMetrics().density);
	}

	private void render() {
		rows.removeAllViews();
		String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
		if (!q.isEmpty()) {
			renderSearch(q);
			return;
		}
		if (expanded != null) {
			renderCategory(expanded);
			return;
		}
		rows.addView(updatedLine());
		if (!installed(WOLVIC))
			rows.addView(button("Install Wolvic first (Setup tab): it opens the web experiences below",
					v -> activity.installFromCatalog("Wolvic")));
		addInstalledRow();
		addAppsRow();
		Map<String, List<WebXR.Site>> byCategory = group(sites);
		for (String category : orderedCategories(byCategory))
			addRow(category, byCategory.get(category));
	}

	/** Called when XR Store comes back to the front or an install finishes. */
	void refreshInstalled() {
		if (loaded)
			render();
	}

	/** The XR apps installed on this Station (tagged for the glasses), to launch. */
	private void addInstalledRow() {
		android.content.pm.PackageManager pm = activity.getPackageManager();
		List<android.content.pm.ApplicationInfo> xrApps = new ArrayList<>();
		for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)) {
			if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 || app.metaData == null
					|| !"uxr".equalsIgnoreCase(app.metaData.getString("com.rokid.sdk", ""))
					|| pm.getLaunchIntentForPackage(app.packageName) == null
					|| app.packageName.equals(SetupPane.BROKER)) // a settings screen, not an XR app
				continue;
			xrApps.add(app);
		}
		if (xrApps.isEmpty())
			return;
		LinearLayout row = rowStrip("Your XR apps", null, xrApps.size());
		for (android.content.pm.ApplicationInfo app : xrApps) {
			View card = card(String.valueOf(pm.getApplicationLabel(app)), "Installed", "",
					v -> Glasses.start(activity, pm.getLaunchIntentForPackage(app.packageName)));
			ImageView icon = new ImageView(activity);
			icon.setImageDrawable(pm.getApplicationIcon(app));
			icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
			icon.setPadding(dp(28), dp(20), dp(28), dp(20));
			((FrameLayout) ((LinearLayout) card).getChildAt(0)).addView(icon, new FrameLayout.LayoutParams(-1, -1));
			((LinearLayout) row.getTag()).addView(card);
		}
	}

	private void addAppsRow() {
		LinearLayout row = rowStrip("Apps to install", null, apps.size());
		for (Catalog.Entry app : apps)
			((LinearLayout) row.getTag()).addView(card(app.name, app.publisher + " · " + app.status, "",
					v -> activity.installFromCatalog(app.name)));
	}

	private void addRow(String category, List<WebXR.Site> items) {
		LinearLayout row = rowStrip(category, category, items.size());
		LinearLayout strip = (LinearLayout) row.getTag();
		for (int i = 0; i < items.size() && i < PER_ROW; i++) {
			WebXR.Site s = items.get(i);
			strip.addView(card(s.name, s.about.isEmpty() ? s.source : s.about, s.image, v -> open(s)));
		}
		if (items.size() > PER_ROW)
			strip.addView(seeAllCard(category, items.size()));
	}

	/** A titled row with a horizontally scrolling strip (returned via the row's tag). */
	private LinearLayout rowStrip(String title, String category, int count) {
		LinearLayout header = new LinearLayout(activity);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(0, dp(18), 0, dp(6));
		TextView name = new TextView(activity);
		name.setText(title);
		name.setTextSize(22);
		header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
		if (category != null && count > PER_ROW) {
			TextView all = new TextView(activity);
			all.setText("See all " + count + " ›");
			all.setTextSize(16);
			all.setPadding(dp(12), dp(6), dp(4), dp(6));
			all.setOnClickListener(v -> { expanded = category; render(); });
			header.addView(all);
		}
		rows.addView(header);

		HorizontalScrollView scroller = new HorizontalScrollView(activity);
		scroller.setHorizontalScrollBarEnabled(false);
		LinearLayout strip = new LinearLayout(activity);
		scroller.addView(strip);
		rows.addView(scroller);
		header.setTag(strip);
		return header;
	}

	private View card(String title, String subtitle, String image, View.OnClickListener onClick) {
		LinearLayout card = new LinearLayout(activity);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(0, 0, dp(12), 0);
		int w = dp(220), h = dp(124);

		FrameLayout cover = new FrameLayout(activity);
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(10));
		bg.setColor(placeholderColor(title));
		cover.setBackground(bg);
		cover.setClipToOutline(true);
		TextView initials = new TextView(activity);
		initials.setText(initials(title));
		initials.setTextSize(30);
		initials.setTextColor(Color.WHITE);
		initials.setGravity(Gravity.CENTER);
		cover.addView(initials, new FrameLayout.LayoutParams(-1, -1));
		if (!image.isEmpty()) {
			ImageView img = new ImageView(activity);
			img.setScaleType(ImageView.ScaleType.CENTER_CROP);
			cover.addView(img, new FrameLayout.LayoutParams(-1, -1));
			images.load(image, img);
		}
		card.addView(cover, new LinearLayout.LayoutParams(w, h));

		TextView name = new TextView(activity);
		name.setText(title);
		name.setTextSize(16);
		name.setSingleLine();
		name.setEllipsize(TextUtils.TruncateAt.END);
		name.setPadding(0, dp(6), 0, 0);
		card.addView(name, new LinearLayout.LayoutParams(w, -2));
		TextView sub = new TextView(activity);
		sub.setText(subtitle);
		sub.setTextSize(13);
		sub.setAlpha(0.7f);
		sub.setMaxLines(2);
		sub.setEllipsize(TextUtils.TruncateAt.END);
		card.addView(sub, new LinearLayout.LayoutParams(w, -2));

		card.setOnClickListener(onClick);
		card.setClickable(true);
		card.setFocusable(true);
		return card;
	}

	private View seeAllCard(String category, int count) {
		return card("See all " + count, category, "", v -> { expanded = category; render(); });
	}

	/** One category as a grid of cards, three per line. */
	private void renderCategory(String category) {
		rows.addView(button("‹ Back to the store", v -> { expanded = null; render(); }));
		List<WebXR.Site> items = group(sites).get(category);
		TextView title = new TextView(activity);
		title.setText(category + (items == null ? "" : " (" + items.size() + ")"));
		title.setTextSize(24);
		title.setPadding(0, dp(12), 0, dp(8));
		rows.addView(title);
		if (items != null)
			grid(items);
	}

	private void renderSearch(String q) {
		List<WebXR.Site> hits = new ArrayList<>();
		for (WebXR.Site s : sites)
			if ((s.name + " " + s.about + " " + s.category).toLowerCase(Locale.ROOT).contains(q))
				hits.add(s);
		TextView title = new TextView(activity);
		title.setText(hits.size() + " results");
		title.setTextSize(20);
		rows.addView(title);
		grid(hits.size() > 90 ? hits.subList(0, 90) : hits);
	}

	private void grid(List<WebXR.Site> items) {
		LinearLayout line = null;
		for (int i = 0; i < items.size(); i++) {
			if (i % 3 == 0) {
				line = new LinearLayout(activity);
				line.setPadding(0, 0, 0, dp(14));
				rows.addView(line);
			}
			WebXR.Site s = items.get(i);
			line.addView(card(s.name, s.about.isEmpty() ? s.source : s.about, s.image, v -> open(s)));
		}
	}

	private Button button(String text, View.OnClickListener onClick) {
		Button b = new Button(activity);
		b.setAllCaps(false);
		b.setText(text);
		b.setOnClickListener(onClick);
		return b;
	}

	private static Map<String, List<WebXR.Site>> group(List<WebXR.Site> sites) {
		Map<String, List<WebXR.Site>> map = new LinkedHashMap<>();
		for (WebXR.Site s : sites)
			map.computeIfAbsent(s.category, k -> new ArrayList<>()).add(s);
		return map;
	}

	private static List<String> orderedCategories(Map<String, List<WebXR.Site>> map) {
		List<String> order = new ArrayList<>();
		for (String c : ROW_ORDER)
			if (map.containsKey(c))
				order.add(c);
		List<String> middle = new ArrayList<>();
		for (String c : map.keySet())
			if (!order.contains(c) && !java.util.Arrays.asList(ROW_LAST).contains(c))
				middle.add(c);
		middle.sort((a, b) -> map.get(b).size() - map.get(a).size());
		if (middle.remove("itch.io: More games"))
			middle.add("itch.io: More games"); // the uncategorized ones after the real genres
		order.addAll(middle);
		for (String c : ROW_LAST)
			if (map.containsKey(c))
				order.add(c);
		return order;
	}

	private static int placeholderColor(String title) {
		float[] hsv = {(title.hashCode() & 0x7fffffff) % 360, 0.45f, 0.45f};
		return Color.HSVToColor(hsv);
	}

	private static String initials(String title) {
		StringBuilder b = new StringBuilder();
		for (String w : title.split("\\s+"))
			if (!w.isEmpty() && b.length() < 2 && Character.isLetterOrDigit(w.charAt(0)))
				b.append(Character.toUpperCase(w.charAt(0)));
		return b.toString();
	}

	// --- actions ---

	private void open(WebXR.Site s) {
		try {
			Glasses.start(activity,
					new Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).setPackage(WOLVIC));
			activity.say("Opening " + s.name + " in Wolvic…");
		} catch (ActivityNotFoundException e) {
			activity.say("Install Wolvic first (top of the store).");
		}
	}

	private boolean installed(String pkg) {
		try {
			activity.getPackageManager().getPackageInfo(pkg, 0);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
