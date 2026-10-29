package com.frenetic.litechunk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Random;

/**
 * A minimal Tetris board, drawn straight onto a canvas.
 * <p>
 * It exists because a conversion of a large world is a two minute wait with nothing to look at beyond a percentage.
 * The design constraint is the opposite of a normal game: this view runs in the UI process while eight conversion
 * threads saturate the same CPU, so it must cost close to nothing when the player is not touching it. There is no
 * per-frame loop — the view invalidates on a gravity tick (every 700ms at level 1) and on input, never at 60fps.
 * <p>
 * Input is gesture based, so no controls take space away from the board: tap to rotate, drag sideways to move, drag
 * down to drop a row at a time, flick down to slam.
 */
public class TetrisView extends View {

    /** Reports the score after every lock, and once more when the stack reaches the top. */
    public interface Listener {
        void onStats(int score, int lines, boolean over);
    }

    private static final int COLS = 10;
    private static final int ROWS = 20;

    /**
     * Four rotations per piece on a 4x4 grid, as 16-bit masks, bit 15 = top-left.
     * <p>
     * Storing the rotations instead of rotating coordinates removes an entire class of off-by-one bugs (and the
     * SRS corner cases) at the cost of 448 bytes of static data.
     */
    private static final int[][] SHAPES = {
            {0x0F00, 0x2222, 0x00F0, 0x4444}, // I
            {0xCC00, 0xCC00, 0xCC00, 0xCC00}, // O
            {0x4E00, 0x4640, 0x0E40, 0x4C40}, // T
            {0x6C00, 0x4620, 0x06C0, 0x8C40}, // S
            {0xC600, 0x2640, 0x0C60, 0x4C80}, // Z
            {0x8E00, 0x6440, 0x0E20, 0x44C0}, // J
            {0x2E00, 0x4460, 0x0E40, 0xC440}, // L
    };

    private static final int[] PIECE_COLORS = {
            R.color.piece_i, R.color.piece_o, R.color.piece_t, R.color.piece_s,
            R.color.piece_z, R.color.piece_j, R.color.piece_l,
    };

    /** Points per simultaneous line count, multiplied by the level. */
    private static final int[] SCORE_TABLE = {0, 100, 300, 500, 800};

    private final byte[] board = new byte[COLS * ROWS];
    private final int[] colors = new int[PIECE_COLORS.length];
    private final Random random = new Random();
    private final int[] bag = new int[SHAPES.length];
    private int bagIndex = SHAPES.length;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (fits(type, rotation, px, py + 1)) {
                py++;
                invalidate();
            } else {
                lock();
            }
            if (running) {
                handler.postDelayed(this, interval());
            }
        }
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Listener listener;

    private int type;
    private int rotation;
    private int px;
    private int py;
    private int score;
    private int lines;
    private int cellPx;
    private boolean over;
    private boolean running;

    private float downX;
    private float downY;
    private float anchorX;
    private float anchorY;
    private long downAt;
    private boolean dragged;

    public TetrisView(Context context) {
        this(context, null);
    }

    public TetrisView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        for (int index = 0; index < PIECE_COLORS.length; index++) {
            colors[index] = ContextCompat.getColor(context, PIECE_COLORS[index]);
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5F, getResources().getDisplayMetrics().density));
        boardPaint.setColor(ContextCompat.getColor(context, R.color.surface_high));
        gridPaint.setColor(ContextCompat.getColor(context, R.color.divider));
        // Always white: the only text this view draws sits on a dark scrim, in both themes.
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setAntiAlias(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        scrimPaint.setColor(0xCC000000);
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    public void startGame() {
        java.util.Arrays.fill(board, (byte) 0);
        score = 0;
        lines = 0;
        over = false;
        running = true;
        bagIndex = SHAPES.length;
        spawn();
        handler.removeCallbacks(tick);
        if (running) {
            handler.postDelayed(tick, interval());
        }
        notifyStats();
        invalidate();
    }

    /** Stops the gravity thread; called when the sheet goes away so nothing keeps posting. */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------

    private void spawn() {
        type = nextType();
        rotation = 0;
        px = 3;
        py = 0;
        if (!fits(type, rotation, px, py)) {
            // The stack reached the spawn area: nothing to clear, the run is over.
            over = true;
            running = false;
            handler.removeCallbacks(tick);
            notifyStats();
        }
    }

    private int nextType() {
        if (bagIndex >= bag.length) {
            for (int index = 0; index < bag.length; index++) {
                bag[index] = index;
            }
            // Fisher-Yates, so all seven pieces come up once per bag and the player never waits for an I piece.
            for (int index = bag.length - 1; index > 0; index--) {
                int swap = random.nextInt(index + 1);
                int value = bag[index];
                bag[index] = bag[swap];
                bag[swap] = value;
            }
            bagIndex = 0;
        }
        return bag[bagIndex++];
    }

    private boolean fits(int shape, int state, int atX, int atY) {
        int mask = SHAPES[shape][state];
        for (int bit = 0; bit < 16; bit++) {
            if ((mask & (1 << (15 - bit))) == 0) {
                continue;
            }
            int x = atX + (bit % 4);
            int y = atY + (bit / 4);
            if (x < 0 || x >= COLS || y >= ROWS) {
                return false;
            }
            if (y >= 0 && board[y * COLS + x] != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean move(int dx, int dy) {
        if (!running || over || !fits(type, rotation, px + dx, py + dy)) {
            return false;
        }
        px += dx;
        py += dy;
        invalidate();
        return true;
    }

    private void rotate() {
        if (!running || over) {
            return;
        }
        int next = (rotation + 1) % 4;
        // The kicks are tried in order of cost: in place first, then one and two cells sideways, then up.
        int[][] kicks = {{0, 0}, {-1, 0}, {1, 0}, {-2, 0}, {2, 0}, {0, -1}};
        for (int[] kick : kicks) {
            if (fits(type, next, px + kick[0], py + kick[1])) {
                rotation = next;
                px += kick[0];
                py += kick[1];
                invalidate();
                return;
            }
        }
    }

    private void hardDrop() {
        if (!running || over) {
            return;
        }
        while (fits(type, rotation, px, py + 1)) {
            py++;
        }
        lock();
    }

    private void lock() {
        int mask = SHAPES[type][rotation];
        for (int bit = 0; bit < 16; bit++) {
            if ((mask & (1 << (15 - bit))) == 0) {
                continue;
            }
            int x = px + (bit % 4);
            int y = py + (bit / 4);
            if (y >= 0 && y < ROWS) {
                board[y * COLS + x] = (byte) (type + 1);
            }
        }
        int cleared = clearLines();
        if (cleared > 0) {
            score += SCORE_TABLE[cleared] * level();
            lines += cleared;
        }
        notifyStats();
        spawn();
        invalidate();
    }

    private int clearLines() {
        int cleared = 0;
        for (int y = ROWS - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) {
                if (board[y * COLS + x] == 0) {
                    full = false;
                    break;
                }
            }
            if (!full) {
                continue;
            }
            cleared++;
            // Shift everything above down one row; the top row becomes empty.
            for (int row = y; row > 0; row--) {
                System.arraycopy(board, (row - 1) * COLS, board, row * COLS, COLS);
            }
            java.util.Arrays.fill(board, 0, COLS, (byte) 0);
            y++; // Re-check the row that just moved into this slot.
        }
        return cleared;
    }

    private int level() {
        return Math.min(10, 1 + lines / 10);
    }

    private long interval() {
        return Math.max(110L, 720L - (level() - 1) * 60L);
    }

    private void notifyStats() {
        if (listener != null) {
            listener.onStats(score, lines, over);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int height = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        cellPx = Math.max(1, Math.min(width / COLS, height / ROWS));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cellPx <= 0) {
            return;
        }
        float boardWidth = (float) cellPx * COLS;
        float boardHeight = (float) cellPx * ROWS;
        float ox = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - boardWidth) / 2F;
        float oy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - boardHeight) / 2F;

        float corner = cellPx * 0.35F;
        rect.set(ox, oy, ox + boardWidth, oy + boardHeight);
        canvas.drawRoundRect(rect, corner, corner, boardPaint);

        for (int column = 1; column < COLS; column++) {
            float x = ox + column * cellPx;
            canvas.drawLine(x, oy, x, oy + boardHeight, gridPaint);
        }
        for (int row = 1; row < ROWS; row++) {
            float y = oy + row * cellPx;
            canvas.drawLine(ox, y, ox + boardWidth, y, gridPaint);
        }

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLS; column++) {
                int value = board[row * COLS + column];
                if (value != 0) {
                    drawBlock(canvas, ox, oy, column, row, colors[value - 1], false);
                }
            }
        }

        if (!over && running) {
            // Landing preview: the one thing that makes a touch screen playable without a 60fps frame budget.
            int ghost = py;
            while (fits(type, rotation, px, ghost + 1)) {
                ghost++;
            }
            int mask = SHAPES[type][rotation];
            for (int bit = 0; bit < 16; bit++) {
                if ((mask & (1 << (15 - bit))) == 0) {
                    continue;
                }
                drawBlock(canvas, ox, oy, px + (bit % 4), ghost + (bit / 4), colors[type], true);
            }
            for (int bit = 0; bit < 16; bit++) {
                if ((mask & (1 << (15 - bit))) == 0) {
                    continue;
                }
                drawBlock(canvas, ox, oy, px + (bit % 4), py + (bit / 4), colors[type], false);
            }
        }

        if (over) {
            rect.set(ox, oy, ox + boardWidth, oy + boardHeight);
            canvas.drawRoundRect(rect, corner, corner, scrimPaint);
            labelPaint.setTextSize(cellPx * 0.85F);
            canvas.drawText("结束了", ox + boardWidth / 2F, oy + boardHeight / 2F - cellPx * 0.4F, labelPaint);
            labelPaint.setTextSize(cellPx * 0.55F);
            canvas.drawText("点一下重来", ox + boardWidth / 2F, oy + boardHeight / 2F + cellPx * 1.1F, labelPaint);
        }
    }

    private void drawBlock(Canvas canvas, float ox, float oy, int column, int row, int color, boolean ghost) {
        if (row < 0) {
            return;
        }
        float inset = cellPx * 0.06F;
        float left = ox + column * cellPx + inset;
        float top = oy + row * cellPx + inset;
        rect.set(left, top, left + cellPx - inset * 2, top + cellPx - inset * 2);
        float corner = cellPx * 0.22F;
        if (ghost) {
            stroke.setColor(color);
            stroke.setAlpha(110);
            canvas.drawRoundRect(rect, corner, corner, stroke);
            return;
        }
        fill.setColor(color);
        canvas.drawRoundRect(rect, corner, corner, fill);
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (over) {
                    startGame();
                    return true;
                }
                // The board sits inside a bottom sheet, which treats any vertical drag as "dismiss me".
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                downX = event.getX();
                downY = event.getY();
                anchorX = downX;
                anchorY = downY;
                downAt = SystemClock.uptimeMillis();
                dragged = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float step = Math.max(cellPx * 0.62F, 12F);
                float dx = event.getX() - anchorX;
                while (Math.abs(dx) >= step) {
                    int direction = dx > 0 ? 1 : -1;
                    if (move(direction, 0)) {
                        dragged = true;
                    }
                    // The anchor advances either way: without it a drag along the wall would loop here forever.
                    anchorX += direction * step;
                    dx = event.getX() - anchorX;
                }
                float dy = event.getY() - anchorY;
                while (dy >= step) {
                    if (move(0, 1)) {
                        dragged = true;
                    }
                    anchorY += step;
                    dy = event.getY() - anchorY;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                long elapsed = SystemClock.uptimeMillis() - downAt;
                float totalY = event.getY() - downY;
                if (!dragged && elapsed < 260L) {
                    rotate();
                } else if (totalY > cellPx * 3F && elapsed < 300L) {
                    hardDrop();
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
