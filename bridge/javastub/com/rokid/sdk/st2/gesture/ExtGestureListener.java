package com.rokid.sdk.st2.gesture;

import android.view.MotionEvent;

// Compile-time copy of Rokid's interface (the real one is in classes2.dex).
public interface ExtGestureListener extends com.rokid.input.GestureEventListener {
	void onHorizontalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d);
	void onLongPress(MotionEvent e);
	void onScroll(MotionEvent a, MotionEvent b, float dx, float dy, boolean f);
	void onSingleTapConfirmed(MotionEvent e);
	void onSingleTapUp(MotionEvent e);
	void onSwipeFromBottom(MotionEvent e);
	void onSwipeFromLeft(MotionEvent e);
	void onSwipeFromRight(MotionEvent e);
	void onSwipeFromTop(MotionEvent e);
	void onVerticalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d);
}
