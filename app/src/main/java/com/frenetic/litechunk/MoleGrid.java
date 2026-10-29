package com.frenetic.litechunk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Random;

/**
 * A 3x3 whack-a-mole grid that fills the time while a conversion runs.
 * <p>
 * Targets appear in a random empty cell and vanish on their own a moment later, so there is nothing to keep track of
 * and nothing to lose by looking away. Two shapes, deliberately opposite: the LiteChunk mark is a hit, the creeper
 * face is a miss.
 * <p>
 * Self-drawn rather than nine {@code ImageView}s: it is one view instead of nine, one layout pass instead of nine, and
 * the appear animation is a scale on a drawable instead of an animator per cell. That matters here — a conversion is
 * already using every core, and this must not be the reason a phone starts to stutter.
 * <p>
 * Nothing runs while the grid is hidden: {@link #stop()} is called when the user collapses it or the run ends, which
 * removes the tick callback entirely.
 */
public class MoleGrid extends View {

    /** What a cell is currently holding. */
    private static final int EMPTY = 0;
    private static final int LOGO = 1;
    private static final int CREEPER = 2;

    private static final int COLUMNS = 3;
    private static final int ROWS = 3;
    private static final int CELLS = COLUMNS * ROWS;

    /** One tick is also the shortest time a target can stay, so 220ms keeps the grid reactive without busy work. */
    private static final long TICK_MS = 220L;
    /** How long a target lives. Short enough that missing one is normal, long enough to react to. */
    private static final long LIFETIME_MIN_MS = 700L;
    private static final long LIFETIME_SPREAD_MS = 600L;
    /** Never more than this many at once: three targets turns it into reflex testing rather than something to fiddle with. */
    private static final int MAX_ACTIVE = 2;
    /** Probability that a tick spawns a new target while under the cap. */
    private static final float SPAWN_CHANCE = 0.5F;
    /** The mark is the one worth tapping, so it shows up a bit more often than the creeper. */
    private static final float LOGO_SHARE = 0.58F;
    /** Appear animation length; a target is fully visible for the rest of its life. */
    private static final long POP_MS = 120L;
    /** Target size relative to a cell. Leaves a visible ring of the well around it. */
    private static final float TARGET_SCALE = 0.52F;
    /**
     * The creeper is drawn smaller than the mark.
     * <p>
     * Both are the same green on purpose: at a glance the grid should read as "something appeared, tap it", and it is
     * the shape that has to be recognised before the finger lands. Size is the second cue, colour is deliberately not
     * one — the whole point is that a wrong tap is easy to make and cheap to laugh at.
     */
    private static final float CREEPER_SCALE = 0.38F;
    private static final float GAP_DP = 8F;

    /** @param luck {@code true} for the LiteChunk mark, {@code false} for the creeper. */
    public interface Listener {
        void onHit(boolean luck);
    }

    private final int[] kind = new int[CELLS];
    private final long[] bornAt = new long[CELLS];
    private final long[] dieAt = new long[CELLS];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable tick = this::onTick;

    private final Drawable cellBackground;
    private final Drawable logoTarget;
    private final Drawable creeperTarget;
    private final Rect bounds = new Rect();
    private final float gap;

    private Listener listener;
    private boolean running;

    public MoleGrid(Context context) {
        this(context, null);
    }

    public MoleGrid(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gap = GAP_DP * getResources().getDisplayMetrics().density;
        cellBackground = ContextCompat.getDrawable(context, R.drawable.bg_mole_cell);
        // Same tint for both shapes: see CREEPER_SCALE.
        logoTarget = tint(ContextCompat.getDrawable(context, R.drawable.ic_logo), R.color.accent);
        creeperTarget = tint(ContextCompat.getDrawable(context, R.drawable.ic_creeper), R.color.accent);
        setClickable(true);
        setContentDescription("打地鼠小游戏：点到 LiteChunk 加分，点到苦力怕扣分");
    }

    private Drawable tint(@Nullable Drawable drawable, int colorRes) {
        if (drawable == null) {
            return null;
        }
        Drawable copy = drawable.mutate();
        copy.setTint(ContextCompat.getColor(getContext(), colorRes));
        return copy;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        clearCells();
        handler.postDelayed(tick, TICK_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
        clearCells();
    }

    public boolean isRunning() {
        return running;
    }

    private void clearCells() {
        for (int index = 0; index < CELLS; index++) {
            kind[index] = EMPTY;
        }
        invalidate();
    }

    private void onTick() {
        if (!running) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean changed = false;
        int active = 0;
        for (int index = 0; index < CELLS; index++) {
            if (kind[index] == EMPTY) {
                continue;
            }
            if (now >= dieAt[index]) {
                kind[index] = EMPTY;
                changed = true;
            } else {
                active++;
            }
        }
        if (active < MAX_ACTIVE && random.nextFloat() < SPAWN_CHANCE) {
            int slot = emptySlot();
            if (slot >= 0) {
                kind[slot] = random.nextFloat() < LOGO_SHARE ? LOGO : CREEPER;
                bornAt[slot] = now;
                dieAt[slot] = now + LIFETIME_MIN_MS + (long) (random.nextFloat() * LIFETIME_SPREAD_MS);
                changed = true;
            }
        }
        if (changed) {
            invalidate();
        }
        handler.postDelayed(tick, TICK_MS);
    }

    private int emptySlot() {
        int count = 0;
        for (int index = 0; index < CELLS; index++) {
            if (kind[index] == EMPTY) {
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        int pick = random.nextInt(count);
        for (int index = 0; index < CELLS; index++) {
            if (kind[index] == EMPTY && pick-- == 0) {
                return index;
            }
        }
        return -1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        // Three square cells plus two gaps add back up to the available width, so the grid is always a square.
        int height = available + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        if (available <= 0) {
            return;
        }
        float cell = (available - gap * (COLUMNS - 1)) / COLUMNS;
        long now = SystemClock.uptimeMillis();
        for (int index = 0; index < CELLS; index++) {
            int row = index / COLUMNS;
            int column = index % COLUMNS;
            float left = getPaddingLeft() + column * (cell + gap);
            float top = getPaddingTop() + row * (cell + gap);
            drawCell(canvas, left, top, cell);

            int content = kind[index];
            if (content == EMPTY) {
                continue;
            }
            Drawable target = content == LOGO ? logoTarget : creeperTarget;
            if (target == null) {
                continue;
            }
            // Grow from 70% to full over the first moments, so a target reads as "just appeared" rather than "was
            // always there and you missed it". No animator: the tick repaints often enough to step it by hand.
            long age = now - bornAt[index];
            float progress = age >= POP_MS ? 1F : Math.max(0.05F, age / (float) POP_MS);
            float base = content == LOGO ? TARGET_SCALE : CREEPER_SCALE;
            float scale = base * (0.7F + 0.3F * progress);
            float size = cell * scale;
            float centerX = left + cell / 2F;
            float centerY = top + cell / 2F;
            bounds.set(Math.round(centerX - size / 2F), Math.round(centerY - size / 2F),
                    Math.round(centerX + size / 2F), Math.round(centerY + size / 2F));
            target.setBounds(bounds);
            target.setAlpha(Math.round(255 * progress));
            target.draw(canvas);
        }
    }

    private void drawCell(Canvas canvas, float left, float top, float size) {
        if (cellBackground == null) {
            return;
        }
        bounds.set(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size));
        cellBackground.setBounds(bounds);
        cellBackground.draw(canvas);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (!running || event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event);
        }
        int index = cellAt(event.getX(), event.getY());
        if (index < 0 || kind[index] == EMPTY) {
            return super.onTouchEvent(event);
        }
        int content = kind[index];
        kind[index] = EMPTY;
        invalidate();
        if (listener != null) {
            listener.onHit(content == LOGO);
        }
        return true;
    }

    /** @return the cell under a point, or {@code -1} when the point falls in a gap or the padding. */
    private int cellAt(float x, float y) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        if (available <= 0) {
            return -1;
        }
        float cell = (available - gap * (COLUMNS - 1)) / COLUMNS;
        float localX = x - getPaddingLeft();
        float localY = y - getPaddingTop();
        if (localX < 0 || localY < 0) {
            return -1;
        }
        int column = (int) (localX / (cell + gap));
        int row = (int) (localY / (cell + gap));
        if (column >= COLUMNS || row >= ROWS) {
            return -1;
        }
        // Reject the gap itself: the divisor above also matches a point a few pixels past the cell edge.
        if (localX - column * (cell + gap) > cell || localY - row * (cell + gap) > cell) {
            return -1;
        }
        return row * COLUMNS + column;
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
