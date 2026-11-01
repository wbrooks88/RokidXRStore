package io.github.wbrooks88.xrstore;

import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * First-run setup, as a checklist. Each step shows whether it is done and offers the action
 * that does it. Checked again whenever XR Store comes to the front.
 */
final class SetupPane {
	static final String BROKER = "org.khronos.openxr.runtime_broker";
	private static final Uri ACTIVE_RUNTIME =
			Uri.parse("content://" + BROKER + "/openxr/1/abi/arm64-v8a/runtimes/active");

	final LinearLayout view;
	private final MainActivity activity;
	private final LinearLayout steps;

	SetupPane(MainActivity activity) {
		this.activity = activity;
		view = new LinearLayout(activity);
		view.setOrientation(LinearLayout.VERTICAL);
		TextView intro = new TextView(activity);
		intro.setText("XR Store sets up OpenXR on this Station once. Do the steps in order; each one "
				+ "turns into a check mark when it's done.");
		intro.setTextSize(16);
		view.addView(intro);
		steps = new LinearLayout(activity);
		steps.setOrientation(LinearLayout.VERTICAL);
		view.addView(steps);
	}

	/** True when everything needed to run OpenXR apps and WebXR is in place. */
	boolean complete() {
		return BridgeBuilder.runtimePresent(activity) && activity.canInstall() && activity.isInstalled(BROKER)
				&& BridgeBuilder.upToDate(activity) && bridgeChosen() && activity.isInstalled(StorePane.WOLVIC);
	}

	void refresh() {
		steps.removeAllViews();
		if (!BridgeBuilder.runtimePresent(activity)) {
			step(false, "This Station has no Rokid OpenXR runtime (com.rokid.openxr.runtime), so XR Store "
					+ "can't set up OpenXR here.", null, null);
			return;
		}
		step(activity.canInstall(), "Allow XR Store to install apps", "Open the setting",
				v -> activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
						Uri.parse("package:" + activity.getPackageName()))));
		step(activity.isInstalled(BROKER), "Install the Khronos OpenXR runtime broker (official app, "
				+ "lets OpenXR apps find a runtime)", "Install", v -> activity.installBroker());
		boolean bridgeInstalled = BridgeBuilder.installed(activity), bridgeCurrent = BridgeBuilder.upToDate(activity);
		step(bridgeCurrent, bridgeInstalled && !bridgeCurrent
						? "Rokid updated its OpenXR runtime: rebuild the bridge"
						: "Build the OpenXR bridge from this Station's Rokid runtime and install it",
				bridgeInstalled ? "Rebuild" : "Build and install", v -> activity.installBridge());
		step(bridgeChosen(), "In the broker, tick \"XR Store OpenXR bridge\" (only that one), then come back",
				"Open the broker", v -> {
					Intent open = new Intent().setComponent(new ComponentName(BROKER, BROKER + ".MainActivity"))
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					activity.startActivity(open);
				});
		step(activity.isInstalled(StorePane.WOLVIC), "Install Wolvic, the XR web browser (for WebXR games)",
				"Install", v -> activity.installFromCatalog("Wolvic"));
		if (complete())
			step(true, "All set. Open the Store tab.", "Go to the store", v -> activity.showStore());
	}

	/** Asks the broker which runtime is active. */
	private boolean bridgeChosen() {
		// The broker requires a projection (it fails on null).
		try (Cursor c = activity.getContentResolver().query(ACTIVE_RUNTIME, new String[] {"package_name"},
				null, null, null)) {
			if (c == null || !c.moveToFirst())
				return false;
			int col = c.getColumnIndex("package_name");
			return col >= 0 && BridgeBuilder.BRIDGE.equals(c.getString(col));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private void step(boolean done, String text, String action, View.OnClickListener onClick) {
		LinearLayout row = new LinearLayout(activity);
		row.setPadding(0, 24, 0, 8);
		TextView mark = new TextView(activity);
		mark.setText(done ? "✓" : "○");
		mark.setTextSize(22);
		mark.setPadding(0, 0, 24, 0);
		row.addView(mark);
		LinearLayout body = new LinearLayout(activity);
		body.setOrientation(LinearLayout.VERTICAL);
		TextView label = new TextView(activity);
		label.setText(text);
		label.setTextSize(18);
		body.addView(label);
		if (action != null && !done) {
			Button b = new Button(activity);
			b.setAllCaps(false);
			b.setText(action);
			b.setOnClickListener(onClick);
			body.addView(b, new LinearLayout.LayoutParams(-2, -2));
		}
		row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
		steps.addView(row);
	}
}
