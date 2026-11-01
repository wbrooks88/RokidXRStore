package io.github.wbrooks88.xrstore;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/** Starting XR apps so they show in the glasses and get the touchpad. */
final class Glasses {
	private Glasses() {}

	/**
	 * Starts an XR app the way Rokid's own launcher does (AppInstallManager.openUxrApp): on the
	 * main display, with its launcher extras. Started from this window instead, the app would
	 * inherit Rokid's virtual window display, and the touchpad would keep driving the Rokid
	 * desktop behind it.
	 *
	 * Rokid then covers this window with a black placeholder that takes the foreground and
	 * pauses the app, so the app is brought to the front once more a moment later (as its
	 * launcher intent, which only resumes it). This window closes after that.
	 */
	static void start(Activity from, Intent launch) {
		ActivityOptions options = ActivityOptions.makeBasic();
		options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
		from.startActivity(withLauncherExtras(launch), options.toBundle());
		String pkg = launch.getPackage() != null ? launch.getPackage()
				: launch.getComponent() != null ? launch.getComponent().getPackageName() : null;
		Intent front = pkg == null ? null : from.getPackageManager().getLaunchIntentForPackage(pkg);
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			try {
				if (front != null)
					from.startActivity(withLauncherExtras(front), options.toBundle());
			} catch (RuntimeException e) {
				Log.w("XRStore", "bring to front failed", e);
			}
			from.finishAndRemoveTask();
		}, 1500);
	}

	private static Intent withLauncherExtras(Intent intent) {
		return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra("FromLauncher", true)
				.putExtra("VERSION_CODE", 0);
	}
}
