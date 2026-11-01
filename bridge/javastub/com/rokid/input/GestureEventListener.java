package com.rokid.input;

import android.view.MotionEvent;

// Compile-time copy of the framework interface (not packaged).
public interface GestureEventListener {
	void onDoubleTap(MotionEvent e);
	void onDown(MotionEvent e);
	void onFling(MotionEvent a, MotionEvent b, float vx, float vy, int d);
	void onHorizontalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d);
	void onLongPress(MotionEvent e);
	void onScroll(MotionEvent a, MotionEvent b, float dx, float dy, boolean f);
	void onSingleTapConfirmed(MotionEvent e);
	void onSingleTapUp(MotionEvent e);
	void onSwipeFromBottom(MotionEvent e);
	void onSwipeFromLeft(MotionEvent e);
	void onSwipeFromRight(MotionEvent e);
	void onSwipeFromTop(MotionEvent e);
	void onUpOrCancel(MotionEvent e);
	void onVerticalFling(MotionEvent a, MotionEvent b, float vx, float vy, int d);
}
