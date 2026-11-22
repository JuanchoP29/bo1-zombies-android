package org.bo1port.zombies;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Hardware-accelerated HD vector overlay placed over SDL2's SurfaceView inside Bo1Activity.
 * - In menus (mode 1): displays D-pad (Up/Down/Left/Right) + Entrar + Atrás and forwards key presses via Bo1Activity.ssKey.
 * - In gameplay (mode 2): 100% touch pass-through to SDL2 (0 ms latency) while rendering crisp HD vector controls & joystick.
 */
public final class TouchHudOverlay extends View {
    // BO1 keyNum_t values (src/ui/keycodes.h)
    private static final int K_ENTER = 13;
    private static final int K_ESCAPE = 27;
    private static final int K_UPARROW = 154;
    private static final int K_DOWNARROW = 155;
    private static final int K_LEFTARROW = 156;
    private static final int K_RIGHTARROW = 157;

    private static final class MenuBtn {
        final String symbol;
        final String caption;
        final int keyCode;
        final boolean primary;
        float cx, cy, r;
        int pointerId = -1;

        MenuBtn(String symbol, String caption, int keyCode, boolean primary) {
            this.symbol = symbol;
            this.caption = caption;
            this.keyCode = keyCode;
            this.primary = primary;
        }
    }

    private final TouchHudRenderer renderer = new TouchHudRenderer();
    private final float[] buf = new float[160];
    private final float[] lastBuf = new float[160];
    private int lastN = -1;
    private volatile boolean nativesReady = false;
    private volatile boolean running = true;
    private int currentMode = 0;

    // Discreet top-center Quick Menu pill (near camera edge)
    private final RectF quickPill = new RectF();
    private final Paint pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int quickPillPid = -1;

    private final MenuBtn[] menuBtns = new MenuBtn[] {
            new MenuBtn("▲", "", K_UPARROW, false),
            new MenuBtn("▼", "", K_DOWNARROW, false),
            new MenuBtn("◄", "", K_LEFTARROW, false),
            new MenuBtn("►", "", K_RIGHTARROW, false),
            new MenuBtn("✓", "ENTRAR", K_ENTER, true),
            new MenuBtn("↩", "ATRÁS", K_ESCAPE, false),
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!nativesReady) {
                postDelayed(this, 150);
                return;
            }
            int n;
            try {
                n = Bo1Activity.touchHudRead(buf);
            } catch (Throwable t) {
                postDelayed(this, 120);
                return;
            }
            boolean changed = (n != lastN);
            if (!changed && n > 0) {
                for (int i = 0; i < n; i++) {
                    if (Math.abs(buf[i] - lastBuf[i]) > 0.0005f) {
                        changed = true;
                        break;
                    }
                }
            }
            int mode = (n >= 12) ? Math.round(buf[0]) : 0;
            if (currentMode == 1 && mode != 1) {
                releaseMenuKeys();
                changed = true;
            }
            currentMode = mode;
            if (changed) {
                if (n > 0) {
                    System.arraycopy(buf, 0, lastBuf, 0, n);
                }
                lastN = n;
                invalidate();
            }

            // Adaptive poll rate: VSync-locked only while fingers actively move touch HUD;
            // low-frequency poll with ZERO UI redraws when idle or playing with a gamepad.
            if (mode == 2) {
                boolean activeTouch = (n >= 12 && buf[4] > 0.5f);
                if (!activeTouch && n >= 12) {
                    int btnCount = Math.round(buf[11]);
                    int pos = 12;
                    for (int i = 0; i < btnCount && pos + 6 <= n; i++) {
                        if (buf[pos + 4] > 0.5f) {
                            activeTouch = true;
                            break;
                        }
                        pos += 6;
                    }
                }
                if (activeTouch || changed) {
                    postOnAnimation(this);
                } else {
                    postDelayed(this, 32);
                }
            } else {
                postDelayed(this, 120);
            }
        }
    };

    public TouchHudOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        pillFill.setStyle(Paint.Style.FILL);
        pillStroke.setStyle(Paint.Style.STROKE);
        pillStroke.setStrokeWidth(2.0f);
        pillText.setTextAlign(Paint.Align.CENTER);
        pillText.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setNativesReady() {
        if (!nativesReady) {
            nativesReady = true;
            post(new Runnable() {
                @Override
                public void run() {
                    removeCallbacks(ticker);
                    postOnAnimation(ticker);
                }
            });
        }
    }

    public void startOverlay() {
        running = true;
        removeCallbacks(ticker);
        postDelayed(ticker, 100);
    }

    public void stopOverlay() {
        running = false;
        removeCallbacks(ticker);
        releaseMenuKeys();
    }

    private void releaseMenuKeys() {
        for (MenuBtn b : menuBtns) {
            if (b.pointerId != -1) {
                b.pointerId = -1;
                if (nativesReady) {
                    try {
                        Bo1Activity.ssKey(b.keyCode, false);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void layoutMenuButtons(int w, int h) {
        float r = Math.max(32f, Math.min(58f, h * 0.062f));
        float step = r * 2.15f;
        float dpadCx = r * 3.1f;
        float dpadCy = h - r * 3.1f;

        // Up, Down, Left, Right
        menuBtns[0].cx = dpadCx;        menuBtns[0].cy = dpadCy - step; menuBtns[0].r = r;
        menuBtns[1].cx = dpadCx;        menuBtns[1].cy = dpadCy + step; menuBtns[1].r = r;
        menuBtns[2].cx = dpadCx - step; menuBtns[2].cy = dpadCy;        menuBtns[2].r = r;
        menuBtns[3].cx = dpadCx + step; menuBtns[3].cy = dpadCy;        menuBtns[3].r = r;

        // Entrar (right-lower) & Atrás (to its left/above)
        float actR = r * 1.12f;
        menuBtns[4].cx = w - actR * 2.0f;
        menuBtns[4].cy = h - actR * 2.3f;
        menuBtns[4].r = actR;

        menuBtns[5].cx = w - actR * 4.5f;
        menuBtns[5].cy = h - actR * 1.9f;
        menuBtns[5].r = actR * 0.92f;

        // Discreet top-center Quick Menu pill (easy reach near camera cutout, never blocks gameplay)
        float pillW = Math.max(88f, Math.min(130f, w * 0.062f));
        float pillH = Math.max(30f, Math.min(44f, h * 0.044f));
        float pillTop = Math.max(6f, h * 0.010f);
        quickPill.set(w * 0.5f - pillW * 0.5f, pillTop, w * 0.5f + pillW * 0.5f, pillTop + pillH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            layoutMenuButtons(w, h);
        }
    }

    private boolean hitQuickPill(float x, float y) {
        float pad = 12f;
        return x >= quickPill.left - pad && x <= quickPill.right + pad
                && y >= quickPill.top - pad && y <= quickPill.bottom + pad;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!nativesReady) {
            return false;
        }

        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);
        float x = event.getX(idx);
        float y = event.getY(idx);

        // Quick Menu top-center pill works in ALL modes (touch, gamepad, menu, or gameplay)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (hitQuickPill(x, y)) {
                quickPillPid = pid;
                invalidate();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (quickPillPid != -1 && (quickPillPid == pid || action == MotionEvent.ACTION_CANCEL)) {
                quickPillPid = -1;
                invalidate();
                if (action != MotionEvent.ACTION_CANCEL && hitQuickPill(x, y) && getContext() instanceof Bo1Activity) {
                    ((Bo1Activity) getContext()).openQuickMenu(currentMode == 2);
                }
                return true;
            }
        } else if (quickPillPid != -1) {
            return true;
        }

        // In gameplay or hidden mode, 100% pass-through to SDL2 (0 ms latency).
        if (currentMode != 1) {
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            for (MenuBtn b : menuBtns) {
                float dx = x - b.cx;
                float dy = y - b.cy;
                if (dx * dx + dy * dy <= (b.r * 1.25f) * (b.r * 1.25f)) {
                    b.pointerId = pid;
                    try {
                        Bo1Activity.ssKey(b.keyCode, true);
                    } catch (Throwable ignored) {
                    }
                    invalidate();
                    return true;
                }
            }
            return false;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = false;
            for (MenuBtn b : menuBtns) {
                if (b.pointerId == pid || action == MotionEvent.ACTION_CANCEL) {
                    if (b.pointerId != -1) {
                        b.pointerId = -1;
                        try {
                            Bo1Activity.ssKey(b.keyCode, false);
                        } catch (Throwable ignored) {
                        }
                        handled = true;
                    }
                }
            }
            if (handled) {
                invalidate();
            }
            return handled;
        }

        // Consume MOVE only if one of our menu buttons is actively held
        for (MenuBtn b : menuBtns) {
            if (b.pointerId != -1) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!nativesReady) {
            return;
        }
        int n = lastN;
        if (n < 0) {
            try {
                n = Bo1Activity.touchHudRead(lastBuf);
                lastN = n;
            } catch (Throwable t) {
                return;
            }
        }
        if (n < 12) {
            currentMode = 0;
            return;
        }

        int mode = Math.round(lastBuf[0]);
        if (currentMode == 1 && mode != 1) {
            releaseMenuKeys();
        }
        currentMode = mode;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Always draw the discreet top-center Quick Menu pill (works with touch AND Bluetooth/USB gamepad)
        boolean pillHeld = (quickPillPid != -1);
        float rx = quickPill.height() * 0.45f;
        pillFill.setColor(pillHeld ? 0xFFF5921E : 0xFF0E141C);
        pillFill.setAlpha(pillHeld ? 210 : 120);
        canvas.drawRoundRect(quickPill, rx, rx, pillFill);

        pillStroke.setColor(pillHeld ? 0xFFFFD08A : 0xFFF5921E);
        pillStroke.setAlpha(pillHeld ? 245 : 145);
        canvas.drawRoundRect(quickPill, rx, rx, pillStroke);

        pillText.setColor(pillHeld ? 0xFF0B0D10 : 0xFFEAECEF);
        pillText.setAlpha(pillHeld ? 250 : 195);
        pillText.setTextSize(quickPill.height() * 0.48f);
        Paint.FontMetrics fm = pillText.getFontMetrics();
        float base = quickPill.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText("⚙ MENÚ", quickPill.centerX(), base, pillText);

        if (mode == 1) {
            // Menu navigation pad
            for (MenuBtn b : menuBtns) {
                renderer.drawMenuButton(canvas, b.cx, b.cy, b.r, b.symbol, b.caption, b.pointerId != -1, b.primary);
            }
            return;
        }

        if (mode != 2) {
            return;
        }

        int visualStyle = Math.round(lastBuf[1]);
        float opacity = lastBuf[2];
        float stickFrac = lastBuf[3];
        boolean stickHeld = lastBuf[4] > 0.5f;
        float stickX = lastBuf[5] * w;
        float stickY = lastBuf[6] * h;
        float stickDX = lastBuf[7];
        float stickDY = lastBuf[8];
        float sprintThresh = lastBuf[9];
        boolean autoSprint = lastBuf[10] > 0.5f;
        int btnCount = Math.round(lastBuf[11]);

        if (stickHeld) {
            float stickR = Math.max(0.06f, stickFrac) * h;
            renderer.drawJoystick(canvas, stickX, stickY, stickR, stickDX, stickDY,
                    opacity, autoSprint, sprintThresh, false, false);
        }

        int pos = 12;
        for (int i = 0; i < btnCount && pos + 6 <= n; i++) {
            float cx = lastBuf[pos++] * w;
            float cy = lastBuf[pos++] * h;
            float halfW = lastBuf[pos++] * w;
            float halfH = lastBuf[pos++] * h;
            boolean held = lastBuf[pos++] > 0.5f;
            int styleId = Math.round(lastBuf[pos++]);
            float r = Math.min(halfW, halfH);
            if (r < 10f) {
                r = halfH;
            }
            renderer.drawControl(canvas, cx, cy, r, styleId, visualStyle, opacity, held, false, false);
        }
    }
}
