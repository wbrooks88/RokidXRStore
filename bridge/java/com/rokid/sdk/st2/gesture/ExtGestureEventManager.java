package com.rokid.sdk.st2.gesture;

import android.util.Log;

import com.rokid.input.GestureEventManager;


/**
 * Stand-in for Rokid's ExtGestureEventManager, loaded first (classes.dex, ahead of Rokid's
 * classes2.dex). It differs from Rokid's in two ways:
 *
 * - The real one calls com.rokid.input.GestureEventManager, a hidden framework API that
 *   ordinary apps may only use when it's exempted (settings global
 *   hidden_api_blacklist_exemptions). This one catches the error Android throws when it isn't,
 *   so OpenXR apps still start; touchpad gestures then just don't reach them.
 * - Touchpad taps become the controller's select button (TapAsSelectListener, TouchSurface).
 */
public final class ExtGestureEventManager {
	private static final String TAG = "XRStoreBridge";
	private static final ExtGestureEventManager INSTANCE = new ExtGestureEventManager();

	private ExtGestureListener original, wrapped;

	private ExtGestureEventManager() {}

	public static ExtGestureEventManager instance() { return INSTANCE; }

	public void addGestureListener(ExtGestureListener listener, String packageName) {
		original = listener;
		wrapped = TapAsSelectListener.wrap(listener);
		try {
			GestureEventManager.instance().addGestureListener(wrapped, packageName);
			Log.i(TAG, "touchpad gestures connected");
		} catch (LinkageError e) {
			Log.w(TAG, "touchpad gestures unavailable (hidden API not exempted): " + e);
		}
	}

	public void removeGestureListener(ExtGestureListener listener) {
		if (wrapped instanceof TapAsSelectListener)
			((TapAsSelectListener) wrapped).touchSurface.hide();
		try {
			GestureEventManager.instance().removeGestureListener(listener == original ? wrapped : listener);
		} catch (LinkageError e) {
			// never connected
		}
	}
}
