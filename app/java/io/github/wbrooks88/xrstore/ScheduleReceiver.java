package io.github.wbrooks88.xrstore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Makes sure the daily catalogue refresh is scheduled after an update or a reboot. */
public class ScheduleReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		RefreshJob.schedule(context);
	}
}
