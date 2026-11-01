package io.github.wbrooks88.xrstore;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Refreshes the store's catalogue once a day in the background (on Wi-Fi), and fetches the
 * cover art of new entries, so the store is current and fast to open even if it hasn't been
 * opened in a while. Scheduled from the store and kept across reboots.
 */
public class RefreshJob extends JobService {
	private static final String TAG = "XRStore";
	private static final int JOB_ID = 1;

	static void schedule(Context c) {
		JobScheduler js = c.getSystemService(JobScheduler.class);
		if (js.getPendingJob(JOB_ID) != null)
			return;
		js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RefreshJob.class))
				.setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
				.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
				.setPersisted(true)
				.build());
	}

	@Override
	public boolean onStartJob(JobParameters params) {
		new Thread(() -> {
			try {
				List<WebXR.Site> sites = StoreData.refresh(this, text -> Log.i(TAG, "refresh: " + text));
				ImageLoader images = new ImageLoader(getCacheDir());
				for (WebXR.Site s : sites)
					if (!s.image.isEmpty())
						images.prefetch(s.image);
				Log.i(TAG, "refreshed " + sites.size() + " entries");
			} catch (RuntimeException e) {
				Log.w(TAG, "refresh failed", e);
			}
			jobFinished(params, false);
		}).start();
		return true;
	}

	@Override
	public boolean onStopJob(JobParameters params) {
		return true; // retry later
	}
}
