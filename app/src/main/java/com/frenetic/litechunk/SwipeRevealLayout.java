package com.frenetic.litechunk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A list row that owns two horizontal gestures.
 * <p>
 * Swipe left: the content slides aside and a strip of action buttons appears underneath. Swipe right: the whole list
 * switches into multi select with this row ticked, the way a mobile file manager does it. Written by hand instead of
 * with {@code ItemTouchHelper}, which owns the drag for the entire list, treats release as a dismiss, and has no room
 * for a second gesture.
 * <p>
 * The slide is a {@code translationX}, not a layout pass: a drag invalidates the row and nothing else, which is what
 * makes the reveal track the finger frame for frame even while a conversion is repainting its progress bar.
 */
public class SwipeRevealLayout extends ViewGroup {

    /** Horizontal travel that counts as a multi select swipe. */
    private static final int SELECT_SLOP_DP = 46;
    /** How far the content follows a right swipe before it snaps back / enters multi select. */
    private static final int MAX_PULL_DP = 26;
    /** Release speed (dp/s) that opens or closes the strip regardless of how far it was dragged. */
    private static final int MIN_FLING_DP = 320;

    /** Material's standard easing. Linear easing is what made the old reveal feel like a rubber band. */
    private static final PathInterpolator EASING = new PathInterpolator(0.2F, 0F, 0F, 1F);

    interface OnRevealListener {
        /** Another row opened: close this one so exactly one strip is visible. */
        void onRowOpened(SwipeRevealLayout row);

        /** The row was swiped right: enter multi select and tick it. */
        void onSelectSwipe(SwipeRevealLayout row);
    }

    private final int touchSlop;
    private final int selectSlop;
    private final int maxPull;
    private final int minFling;

    private View content;
    private View actions;
    private int actionsWidth;

    /** How much of the action strip is revealed. */
    private float offset;
    /** How far the content follows a right swipe, so the gesture is visible before it commits. */
    private float pull;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean horizontalClaimed;
    /** Set once a gesture has been turned into a select swipe, so the same gesture cannot also open the row. */
    private boolean gestureConsumed;
    /** The gesture started on the content of an open row, so releasing it should close the strip. */
    private boolean tapToClose;

    private VelocityTracker velocityTracker;
    private ValueAnimator animator;

    private OnRevealListener revealListener;

    public SwipeRevealLayout(Context context) {
        this(context, null);
    }

    public SwipeRevealLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        selectSlop = (int) (SELECT_SLOP_DP * density);
        maxPull = (int) (MAX_PULL_DP * density);
        minFling = (int) (MIN_FLING_DP * density);
    }

    void setOnRevealListener(OnRevealListener listener) {
        this.revealListener = listener;
    }

    boolean isOpen() {
        return offset > 0.5F;
    }

    void close() {
        animateTo(0F, 0F);
    }

    void closeNow() {
        cancelAnimator();
        offset = 0F;
        pull = 0F;
        applyTransforms();
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement / layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        content = getChildAt(0);
        actions = getChildAt(1);

        if (content != null) {
            content.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    heightMode == MeasureSpec.UNSPECIFIED
                            ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            : MeasureSpec.makeMeasureSpec(heightSize, heightMode));
        }
        // A gone strip means multi select is on: there is nothing to reveal, and the row must stop eating
        // horizontal gestures so pages can still be swiped.
        if (actions != null && actions.getVisibility() != View.GONE) {
            actions.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    heightMode == MeasureSpec.UNSPECIFIED
                            ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            : MeasureSpec.makeMeasureSpec(heightSize, heightMode));
            actionsWidth = actions.getMeasuredWidth();
        } else {
            actionsWidth = 0;
        }

        int measuredHeight = content != null ? content.getMeasuredHeight() : 0;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = heightSize;
        }
        setMeasuredDimension(width, measuredHeight);
        if (offset > actionsWidth) {
            offset = actionsWidth;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        // Children sit at their resting position; the drag is a translation, so scrolling a list never pays for a
        // second layout pass per frame.
        if (content != null) {
            content.layout(0, 0, width, bottom - top);
        }
        if (actions != null) {
            actions.layout(width, 0, width + actionsWidth, bottom - top);
        }
        applyTransforms();
    }

    private void applyTransforms() {
        if (content != null) {
            content.setTranslationX(pull - offset);
        }
        if (actions != null) {
            actions.setTranslationX(-offset);
            // Fade the strip in with the reveal instead of popping it out from under the row.
            actions.setAlpha(actionsWidth <= 0 ? 0F : Math.min(1F, offset / actionsWidth));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureConsumed = false;
                dragging = false;
                horizontalClaimed = false;
                cancelAnimator();
                if (actionsWidth <= 0) {
                    // Multi select is active: leave every horizontal gesture to the pager.
                    tapToClose = false;
                    return false;
                }
                // The rows live inside a ViewPager2, whose internal RecyclerView is asked about every MOVE first.
                // Anything it sees past its own slop becomes a page swipe and this row gets cancelled, so the row
                // claims the gesture now and hands it back if it turns out to be vertical.
                disallowPagerIntercept(true);
                // Tapping the *content* of an open row closes it. Taps on the revealed strip are left alone: those
                // are the action buttons. The close is committed on release, never here: starting the animation on
                // touch-down made a quick tap read as "still open" and bounce the strip straight back out.
                tapToClose = offset > 0F && event.getX() < getWidth() - offset;
                return tapToClose;
            case MotionEvent.ACTION_MOVE: {
                if (actionsWidth <= 0) {
                    return false;
                }
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dy > touchSlop && dy > dx) {
                    // Vertical: a list scroll. Ancestors track from the same ACTION_DOWN, so the gesture continues.
                    disallowPagerIntercept(false);
                    return false;
                }
                if (dx > touchSlop && dx > dy) {
                    horizontalClaimed = true;
                    trackVelocity(event);
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    private void disallowPagerIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (actionsWidth <= 0 && !dragging && offset <= 0F) {
            // Nothing to reveal and no strip open: let the tap listener on the content run.
            return false;
        }
        trackVelocity(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                gestureConsumed = false;
                disallowPagerIntercept(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (gestureConsumed) {
                    return true;
                }
                float delta = event.getX() - downX;
                if (Math.abs(delta) > touchSlop || Math.abs(event.getY() - downY) > touchSlop) {
                    // The finger moved: this is a drag, not a tap on the content.
                    tapToClose = false;
                }
                if (offset > 0F) {
                    // An open row follows the finger both ways, so a right swipe puts it back.
                    offset = clamp(offset - delta, 0F, actionsWidth);
                    pull = 0F;
                    dragging = true;
                    downX = event.getX();
                    applyTransforms();
                    return true;
                }
                if (delta < 0F) {
                    offset = clamp(-delta, 0F, actionsWidth);
                    pull = 0F;
                    dragging = true;
                    downX = event.getX();
                    applyTransforms();
                    return true;
                }
                // Right swipe: rubber band a little, then commit to multi select.
                pull = Math.min(maxPull * 0.55F, delta * 0.45F);
                applyTransforms();
                if (delta > selectSlop) {
                    commitSelectSwipe();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (gestureConsumed) {
                    return true;
                }
                if (tapToClose) {
                    tapToClose = false;
                    animateTo(0F, 0F);
                    return true;
                }
                float velocityX = readVelocity();
                if (offset > 0F || dragging) {
                    // A flick decides on its own; a slow release is decided by how far the strip got.
                    boolean open;
                    if (velocityX < -minFling) {
                        open = true;
                    } else if (velocityX > minFling) {
                        open = false;
                    } else {
                        open = offset > actionsWidth * 0.5F;
                    }
                    if (open) {
                        if (revealListener != null) {
                            revealListener.onRowOpened(this);
                        }
                        animateTo(actionsWidth, velocityX);
                    } else {
                        animateTo(0F, velocityX);
                    }
                    dragging = false;
                    return true;
                }
                if (pull > 0F) {
                    // A short right swipe that never reached the threshold: snap back, swallow the tap.
                    pull = 0F;
                    applyTransforms();
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    private void commitSelectSwipe() {
        gestureConsumed = true;
        pull = 0F;
        offset = 0F;
        applyTransforms();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (revealListener != null) {
            revealListener.onSelectSwipe(this);
        }
    }

    private void trackVelocity(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
    }

    private float readVelocity() {
        if (velocityTracker == null) {
            return 0F;
        }
        velocityTracker.computeCurrentVelocity(1000, minFling * 6F);
        float value = velocityTracker.getXVelocity();
        velocityTracker.recycle();
        velocityTracker = null;
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    // ---------------------------------------------------------------------------------------------
    // Animation
    // ---------------------------------------------------------------------------------------------

    private void animateTo(float target, float velocity) {
        cancelAnimator();
        float distance = Math.abs(target - offset);
        if (distance < 0.5F) {
            offset = target;
            applyTransforms();
            return;
        }
        // A flick should feel immediate; a small correction should not crawl. Both are budgeted from the distance
        // left, so the speed on screen stays roughly constant instead of "short move = slow move".
        int duration = (int) (80F + 130F * Math.min(1F, distance / Math.max(1F, actionsWidth)));
        if (Math.abs(velocity) > minFling * 2F) {
            duration = Math.min(duration, 130);
        }
        animator = ValueAnimator.ofFloat(offset, target);
        animator.setDuration(duration);
        animator.setInterpolator(EASING);
        animator.addUpdateListener(animation -> {
            offset = (float) animation.getAnimatedValue();
            applyTransforms();
        });
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "SwipeRevealLayout{offset=" + offset + ", pull=" + pull + ", claimed=" + horizontalClaimed + "}";
    }
}
