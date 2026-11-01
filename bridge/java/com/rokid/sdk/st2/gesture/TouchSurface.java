package com.rokid.sdk.st2.gesture;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * An invisible, full-screen touch window on the Station's own display, owned by the app.
 *
 * The Station's touchpad is its built-in touchscreen. For a non-Unity OpenXR app nothing
 * on that display takes touches (the app's own window is GONE), and Rokid's system gesture
 * service only passes swipes on (taps need persist.rkd.gesture_* flags that are off). So
 * taps go nowhere. This window receives them and presses the controller's buttons through
 * Rokid's VirtualGamepadController.handleGestureEvent: a tap is select, a long press is menu,
 * a swipe re-centers the pointer.
 */
final class TouchSurface {
	private static final String TAG = "XRStoreBridge";
	private static final int KEYCODE_DPAD_CENTER = 23, KEYCODE_MENU = 82, KEYCODE_SLASH = 76;
	private static final int PRESSED = 1, RELEASED = 0;

	private final Object controller;
	private final Method handleGestureEvent;
	private Dialog dialog;

	TouchSurface(Object controller, Method handleGestureEvent) {
		this.controller = controller;
		this.handleGestureEvent = handleGestureEvent;
	}

	void show(Activity activity) {
		if (dialog != null || activity == null)
			return;
		View surface = new View(activity);
		GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
			@Override public boolean onDown(MotionEvent e) { return true; }
			@Override public boolean onSingleTapUp(MotionEvent e) { click(KEYCODE_DPAD_CENTER); return true; }
			@Override public void onLongPress(MotionEvent e) { click(KEYCODE_MENU); }
			// Rokid maps SLASH to "reset the Station's pointer to straight ahead", which its own
			// system swipe gesture also triggers.
			@Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
				click(KEYCODE_SLASH);
				return true;
			}
		});
		surface.setOnTouchListener((v, e) -> detector.onTouchEvent(e));

		dialog = new Dialog(activity);
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		dialog.setContentView(surface);
		dialog.setCancelable(false);
		Window w = dialog.getWindow();
		w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
		w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		// Touches only: leave key focus with the app.
		w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
				| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
		try {
			dialog.show();
			Log.i(TAG, "touch surface shown on display " + activity.getDisplay().getDisplayId());
		} catch (RuntimeException e) {
			Log.w(TAG, "touch surface failed: " + e);
			dialog = null;
		}
	}

	void hide() {
		if (dialog != null) {
			dialog.dismiss();
			dialog = null;
		}
	}

	private void click(int keyCode) {
		Log.i(TAG, keyCode == KEYCODE_MENU ? "long press -> menu"
				: keyCode == KEYCODE_SLASH ? "swipe -> recenter" : "tap -> select");
		press(keyCode, PRESSED);
		new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> press(keyCode, RELEASED), 80);
	}

	private void press(int keyCode, int state) {
		try {
			handleGestureEvent.invoke(controller, keyCode, state);
		} catch (ReflectiveOperationException e) {
			Log.w(TAG, "button press failed: " + e);
		}
	}
}
