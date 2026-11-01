package io.github.wbrooks88.xrstore;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * XR Store: sets up OpenXR on a Rokid Station 2 (SetupPane), offers apps and WebXR
 * experiences (StorePane), and installs any OpenXR APK so it runs on the Station: it adds the
 * Rokid "uxr" tag the Station requires before an app may draw on the glasses, swaps Meta's
 * OpenXR loader for Khronos's where needed, signs with this device's key, and installs.
 * APKs come from the store, the "Choose APK" picker, or Open with / Share.
 */
public class MainActivity extends Activity {
	private static final String TAG = "XRStore";
	private static final String ACTION_INSTALL_STATUS = "io.github.wbrooks88.xrstore.INSTALL_STATUS";
	private static final int PICK_APK = 1;

	private final Handler main = new Handler(Looper.getMainLooper());
	private TextView status;
	private Button uninstallButton;
	private String conflictingPackage;
	private SetupPane setup;
	private StorePane store;
	private LinearLayout apps;
	private List<Catalog.Entry> catalog = new java.util.ArrayList<>();

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		LinearLayout ui = new LinearLayout(this);
		ui.setOrientation(LinearLayout.VERTICAL);
		ui.setPadding(48, 48, 48, 48);

		TextView title = new TextView(this);
		title.setText("XR Store");
		title.setTextSize(28);
		ui.addView(title);

		LinearLayout tabs = new LinearLayout(this);
		Button storeTab = new Button(this), appsTab = new Button(this), setupTab = new Button(this);
		storeTab.setText("Store");
		appsTab.setText("Install an APK");
		setupTab.setText("Setup");
		tabs.addView(storeTab);
		tabs.addView(appsTab);
		tabs.addView(setupTab);
		ui.addView(tabs);

		status = new TextView(this);
		status.setTextSize(18);
		status.setPadding(0, 24, 0, 24);
		ui.addView(status);

		try (InputStream json = getAssets().open("catalog.json")) {
			catalog = Catalog.load(json);
		} catch (Exception e) {
			Log.e(TAG, "catalog", e);
		}

		setup = new SetupPane(this);
		store = new StorePane(this, catalog);
		apps = appsPane();
		ui.addView(setup.view);
		ui.addView(store.view);
		ui.addView(apps);
		storeTab.setOnClickListener(v -> showStore());
		appsTab.setOnClickListener(v -> show(apps));
		setupTab.setOnClickListener(v -> show(setup.view));

		ScrollView scroll = new ScrollView(this);
		scroll.addView(ui);
		setContentView(scroll);
		setup.refresh();
		if (setup.complete())
			showStore();
		else
			show(setup.view);
		handle(getIntent());
	}

	private LinearLayout appsPane() {
		LinearLayout pane = new LinearLayout(this);
		pane.setOrientation(LinearLayout.VERTICAL);
		TextView help = new TextView(this);
		help.setText("Install any OpenXR app (APK): choose the file here, or open a downloaded APK with "
				+ "XR Store. It's adapted for the Station and appears under Your XR apps in the store.");
		help.setTextSize(16);
		pane.addView(help);
		Button choose = new Button(this);
		choose.setText("Choose APK…");
		choose.setTextSize(22);
		choose.setOnClickListener(v -> pickApk());
		pane.addView(choose);
		uninstallButton = new Button(this);
		uninstallButton.setOnClickListener(v -> startActivity(new Intent(
				Intent.ACTION_DELETE, Uri.parse("package:" + conflictingPackage))));
		pane.addView(uninstallButton);
		return pane;
	}

	private void show(View pane) {
		for (View v : new View[] {setup.view, store.view, apps})
			v.setVisibility(v == pane ? View.VISIBLE : View.GONE);
		if (pane == store.view)
			store.show();
	}

	void showStore() {
		show(store.view);
	}

	@Override
	protected void onResume() {
		super.onResume();
		refreshButtons();
		setup.refresh();
		store.refreshInstalled();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		handle(intent);
	}

	boolean canInstall() {
		return getPackageManager().canRequestPackageInstalls();
	}

	boolean isInstalled(String pkg) {
		try {
			getPackageManager().getPackageInfo(pkg, 0);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	// --- installing ---

	/** Installs the catalog entry named {@code name}, if there is one. */
	void installFromCatalog(String name) {
		for (Catalog.Entry e : catalog)
			if (e.name.equals(name))
				background("install " + name, () -> {
					File work = workDir();
					File in = new File(work, "in.apk");
					Catalog.download(e, in, work, this::say);
					installFile(in, work);
				});
	}

	/** The Khronos broker, straight from its official release (installed as published). */
	void installBroker() {
		background("install broker", () -> {
			File work = workDir();
			File apk = new File(work, "broker.apk");
			Catalog.downloadGitLab("gitlab.freedesktop.org", "monado/utilities/openxr-android-broker",
					"\\.apk$", apk, this::say);
			installApk(apk, SetupPane.BROKER);
		});
	}

	void installBridge() {
		background("build bridge", () -> {
			File work = workDir();
			File apk = new File(work, "bridge.apk");
			say("Building the OpenXR bridge from this Station's Rokid runtime…");
			BridgeBuilder.build(this, apk, work, DeviceKey.get());
			if (isInstalledWithOtherSigner(BridgeBuilder.BRIDGE, apk)) {
				conflict(BridgeBuilder.BRIDGE);
				return;
			}
			installApk(apk, BridgeBuilder.BRIDGE);
		});
	}

	private void background(String what, ThrowingRunnable job) {
		conflictingPackage = null;
		refreshButtons();
		new Thread(() -> {
			try {
				job.run();
			} catch (Exception e) {
				Log.e(TAG, what + " failed", e);
				say("Failed: " + e.getMessage());
			}
		}).start();
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private void refreshButtons() {
		if (uninstallButton == null)
			return;
		uninstallButton.setVisibility(conflictingPackage == null ? View.GONE : View.VISIBLE);
		if (conflictingPackage != null)
			uninstallButton.setText("Uninstall the existing " + conflictingPackage + "…");
	}

	private void pickApk() {
		Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		pick.addCategory(Intent.CATEGORY_OPENABLE);
		pick.setType("*/*");
		pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
				"application/vnd.android.package-archive", "application/octet-stream"});
		startActivityForResult(pick, PICK_APK);
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null)
			start(data.getData());
	}

	private void handle(Intent intent) {
		if (intent == null)
			return;
		if (intent.getBooleanExtra("export_bridge", false)) {
			// For testing from adb: builds the bridge into this app's external files folder.
			background("export bridge", () -> {
				File out = new File(getExternalFilesDir(null), "bridge.apk");
				BridgeBuilder.build(this, out, workDir(), DeviceKey.get());
				say("Bridge written to " + out);
			});
			return;
		}
		if (ACTION_INSTALL_STATUS.equals(intent.getAction())) {
			onInstallStatus(intent);
			return;
		}
		Uri uri = null;
		if (Intent.ACTION_VIEW.equals(intent.getAction()))
			uri = intent.getData();
		else if (Intent.ACTION_SEND.equals(intent.getAction()))
			uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
		if (uri != null)
			start(uri);
	}

	void say(String text) {
		main.post(() -> status.setText(text));
	}

	private void start(Uri uri) {
		show(apps);
		say("Reading APK…");
		background("install file", () -> {
			File work = workDir();
			File in = new File(work, "in.apk");
			try (InputStream s = getContentResolver().openInputStream(uri);
			     OutputStream o = new FileOutputStream(in)) {
				byte[] buf = new byte[1 << 16];
				for (int n; (n = s.read(buf)) > 0; )
					o.write(buf, 0, n);
			}
			installFile(in, work);
		});
	}

	private File workDir() {
		File work = new File(getCacheDir(), "work");
		work.mkdirs();
		for (File f : work.listFiles())
			f.delete();
		return work;
	}

	/** Adapts an app for the Station (ApkTagger) and installs it. */
	private void installFile(File in, File work) throws Exception {
		PackageInfo archive = getPackageManager().getPackageArchiveInfo(in.getPath(), 0);
		if (archive == null)
			throw new Exception("that file isn't an Android app (APK)");
		String pkg = archive.packageName;
		say("Adapting " + pkg + " for the Station…");
		File apk = new File(work, "out.apk");
		byte[] loader;
		try (InputStream s = getAssets().open("khronos/libopenxr_loader.so")) {
			loader = ApkTagger.read(s);
		}
		try {
			ApkTagger.tag(in, apk, work, DeviceKey.get(), loader, this::say);
		} catch (AxmlPatcher.AlreadyTaggedException e) {
			apk = in; // already a Rokid XR app, install as-is
		}
		if (isInstalledWithOtherSigner(pkg, apk)) {
			conflict(pkg);
			return;
		}
		installApk(apk, pkg);
	}

	private void conflict(String pkg) {
		conflictingPackage = pkg;
		main.post(() -> {
			show(apps);
			refreshButtons();
		});
		say(pkg + " is already installed with a different signature. Uninstall it with the button "
				+ "below, then try again.");
	}

	private void installApk(File apk, String pkg) throws Exception {
		say("Installing " + pkg + "…");
		PackageInstaller installer = getPackageManager().getPackageInstaller();
		PackageInstaller.SessionParams params =
				new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		params.setAppPackageName(pkg);
		int id = installer.createSession(params);
		try (PackageInstaller.Session session = installer.openSession(id)) {
			try (OutputStream o = session.openWrite("base.apk", 0, apk.length());
			     InputStream s = new FileInputStream(apk)) {
				byte[] buf = new byte[1 << 16];
				for (int n; (n = s.read(buf)) > 0; )
					o.write(buf, 0, n);
				session.fsync(o);
			}
			Intent done = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS)
					.putExtra("pkg", pkg);
			PendingIntent pi = PendingIntent.getActivity(this, id, done,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
			session.commit(pi.getIntentSender());
		}
	}

	private boolean isInstalledWithOtherSigner(String pkg, File apk) {
		PackageManager pm = getPackageManager();
		try {
			PackageInfo installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
			PackageInfo incoming = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
			if (installed.signingInfo == null || incoming == null || incoming.signingInfo == null)
				return false;
			return !Arrays.equals(installed.signingInfo.getApkContentsSigners(),
					incoming.signingInfo.getApkContentsSigners());
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private void onInstallStatus(Intent intent) {
		int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
		String pkg = intent.getStringExtra("pkg");
		switch (code) {
		case PackageInstaller.STATUS_PENDING_USER_ACTION:
			Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
			if (confirm != null)
				startActivity(confirm);
			say("Confirm the install of " + pkg + "…");
			break;
		case PackageInstaller.STATUS_SUCCESS:
			if (BridgeBuilder.BRIDGE.equals(pkg))
				BridgeBuilder.markInstalled(this);
			say("Installed " + pkg + ".");
			setup.refresh();
			store.refreshInstalled();
			break;
		default:
			say("Install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
		}
	}
}
