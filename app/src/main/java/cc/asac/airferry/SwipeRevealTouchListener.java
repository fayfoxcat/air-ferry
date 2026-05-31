package cc.asac.airferry;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.recyclerview.widget.RecyclerView;

public class SwipeRevealTouchListener implements RecyclerView.OnItemTouchListener {
    private final RecyclerView recyclerView;
    private final int actionPanelWidth;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private int activePosition = RecyclerView.NO_POSITION;
    private View activeChild;
    private float currentDx = 0;
    private boolean isDragging = false;
    private float startX, startY;

    public SwipeRevealTouchListener(RecyclerView rv, int actionWidthDp) {
        this.recyclerView = rv;
        this.actionPanelWidth = (int) (actionWidthDp * rv.getResources().getDisplayMetrics().density);
        this.scroller = new OverScroller(rv.getContext());
        this.gestureDetector = new GestureDetector(rv.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (activePosition == RecyclerView.NO_POSITION) return false;
                        currentDx -= distanceX;
                        currentDx = Math.max(-actionPanelWidth, Math.min(0, currentDx));
                        applyTranslation();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (activePosition == RecyclerView.NO_POSITION) return false;
                        boolean shouldOpen = Math.abs(currentDx) > actionPanelWidth * 0.4f
                                || velocityX < -2000;
                        animateTo(shouldOpen ? -actionPanelWidth : 0);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (activePosition != RecyclerView.NO_POSITION && currentDx < -10) {
                            animateTo(0);
                            return true;
                        }
                        return false;
                    }
                });
        gestureDetector.setIsLongpressEnabled(false);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getX();
                startY = e.getY();
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child != null) {
                    int pos = rv.getChildAdapterPosition(child);
                    if (activePosition != RecyclerView.NO_POSITION && activePosition != pos) {
                        View oldContent = activeChild != null
                                ? activeChild.findViewById(R.id.item_content) : null;
                        if (oldContent != null) {
                            oldContent.animate().translationX(0).setDuration(150).start();
                        }
                        currentDx = 0;
                    }
                    activePosition = pos;
                    activeChild = child;
                }
                isDragging = false;
                currentDx = getCurrentTranslation();
                break;
            case MotionEvent.ACTION_MOVE:
                if (activePosition == RecyclerView.NO_POSITION) break;
                float dx = Math.abs(e.getX() - startX);
                float dy = Math.abs(e.getY() - startY);
                if (!isDragging && dx > dy * 1.5f && dx > ViewConfiguration.get(rv.getContext()).getScaledTouchSlop()) {
                    isDragging = true;
                    rv.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (isDragging) return true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    rv.requestDisallowInterceptTouchEvent(false);
                }
                isDragging = false;
                break;
        }
        gestureDetector.onTouchEvent(e);
        return isDragging;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        gestureDetector.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!scroller.isFinished()) return;
            boolean shouldOpen = Math.abs(currentDx) > actionPanelWidth * 0.4f;
            animateTo(shouldOpen ? -actionPanelWidth : 0);
            isDragging = false;
            rv.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    }

    private float getCurrentTranslation() {
        if (activeChild == null) return 0;
        View content = activeChild.findViewById(R.id.item_content);
        return content != null ? content.getTranslationX() : 0;
    }

    private void applyTranslation() {
        if (activeChild == null) return;
        View content = activeChild.findViewById(R.id.item_content);
        if (content != null) content.setTranslationX(currentDx);
    }

    private void animateTo(int target) {
        int startX = (int) currentDx;
        scroller.startScroll(startX, 0, target - startX, 0, 250);
        recyclerView.postOnAnimation(this::scrollTick);
    }

    private void scrollTick() {
        if (scroller.computeScrollOffset()) {
            currentDx = scroller.getCurrX();
            applyTranslation();
            recyclerView.postOnAnimation(this::scrollTick);
        } else {
            currentDx = scroller.getFinalX();
            applyTranslation();
            if (currentDx == 0) {
                activePosition = RecyclerView.NO_POSITION;
                activeChild = null;
            }
        }
    }

    public void closeActiveItem() {
        if (activePosition != RecyclerView.NO_POSITION) {
            animateTo(0);
        }
    }

    public void reset() {
        activePosition = RecyclerView.NO_POSITION;
        activeChild = null;
        currentDx = 0;
        isDragging = false;
    }
}