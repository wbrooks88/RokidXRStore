package com.rokid.sdk.st2.gesture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Wraps the gesture listener of Rokid's VirtualGamepadController, forwarding everything,
 * and turns a single tap into a DPAD_CENTER press via the controller's handleGestureEvent
 * (the runtime reports that as the controller's select button). Rokid's own listener
 * ignores taps unless the app is a Unity app.
 */
final class TapAsSelectListener implements ExtGestureListener {
	private static final String TAG = "XRStoreBridge";
	private static final int KEYCODE_DPAD_CENTER = 23;
	private static final int PRESSED = 1, RELEASED = 0;

	final ExtGestureListener original;
	private final Object controller;
	private final Method handleGestureEvent;
	private final Handler main = new Handler(Looper.getMainLooper());

	final TouchSurface touchSurface;

	private TapAsSelectListener(ExtGestureListener original, Object controller, Method handleGestureEvent) {
		this.original = original;
		this.controller = controller;
		this.handleGestureEvent = handleGestureEvent;
		touchSurface = new TouchSurface(controller, handleGestureEvent);
		// VirtualGamepadController is a Fragment of the app's activity.
		if (controller instanceof android.app.Fragment)
			touchSurface.show(((android.app.Fragment) controller).getActivity());
	}

	/** The wrapper, or the original listener if it isn't Rokid's controller listener. */
	static ExtGestureListener wrap(ExtGestureListener listener) {
		try {
			Field outer = listener.getClass().getDeclaredField("this$0");
			outer.setAccessible(true);
			Object controller = outer.get(listener);
			Method handle = controller.getClass().getDeclaredMethod("handleGestureEvent", int.class, int.class);
			handle.setAccessible(true);
			return new TapAsSelectListener(listener, controller, handle);
		} catch (ReflectiveOperationException e) {
			Log.w(TAG, "tap-to-select unavailable: " + e);
			return listener;
		}
	}

	private void key(int state) {
		try {
			handleGestureEvent.invoke(controller, KEYCODE_DPAD_CENTER, state);
		} catch (ReflectiveOperationException e) {
			Log.w(TAG, "tap-to-select failed: " + e);
		}
	}

	/** Calls a GestureEventListener-only method on Rokid's listener class, if it has one. */
	private void call(String name, Class<?>[] types, Object... args) {
		try {
			Method m = original.getClass().getDeclaredMethod(name, types);
			m.setAccessible(true);
			m.invoke(original, args);
		} catch (ReflectiveOperationException e) {
			// not implemented there
		}
	}

	@Override public void onSingleTapUp(MotionEvent e) {
		key(PRESSED);
		main.postDelayed(() -> key(RELEASED), 80);
		original.onSingleTapUp(e);
	}

	@Override public void onSingleTapConfirmed(MotionEvent e) { original.onSingleTapConfirmed(e); }
	@Override public void onLongPress(MotionEvent e) { original.onLongPress(e); }
	@Override public void onScroll(MotionEvent a, MotionEvent b, float dx, float dy, boolean f) { original.onScroll(a, b, dx, dy, f); }
	@Override public void onHorizontalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d) { original.onHorizontalFling(a, b, vx, vy, d); }
	@Override public void onVerticalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d) { original.onVerticalFling(a, b, vx, vy, d); }
	@Override public void onSwipeFromBottom(MotionEvent e) { original.onSwipeFromBottom(e); }
	@Override public void onSwipeFromLeft(MotionEvent e) { original.onSwipeFromLeft(e); }
	@Override public void onSwipeFromRight(MotionEvent e) { original.onSwipeFromRight(e); }
	@Override public void onSwipeFromTop(MotionEvent e) { original.onSwipeFromTop(e); }

	private static final Class<?>[] ONE = {MotionEvent.class};
	private static final Class<?>[] FLING = {MotionEvent.class, MotionEvent.class, float.class, float.class, int.class};
	@Override public void onDoubleTap(MotionEvent e) { call("onDoubleTap", ONE, e); }
	@Override public void onDown(MotionEvent e) { call("onDown", ONE, e); }
	@Override public void onUpOrCancel(MotionEvent e) { call("onUpOrCancel", ONE, e); }
	@Override public void onFling(MotionEvent a, MotionEvent b, float vx, float vy, int d) { call("onFling", FLING, a, b, vx, vy, d); }
}
