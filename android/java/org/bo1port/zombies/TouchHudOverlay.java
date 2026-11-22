package org.bo1port.zombies;

import android.content.Context;
import android.graphics.Canvas;
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
    private volatile boolean nativesReady = false;
    private volatile boolean running = true;
    private int currentMode = 0;

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
            if (nativesReady) {
                invalidate();
                postOnAnimation(this);
            } else {
                postDelayed(this, 150);
            }
        }
    };

    public TouchHudOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
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
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            layoutMenuButtons(w, h);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // In gameplay or hidden mode, 100% pass-through to SDL2 (0 ms latency).
        if (currentMode != 1 || !nativesReady) {
            return false;
        }

        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);
        float x = event.getX(idx);
        float y = event.getY(idx);

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
        int n;
        try {
            n = Bo1Activity.touchHudRead(buf);
        } catch (Throwable t) {
            return;
        }
        if (n < 12) {
            currentMode = 0;
            return;
        }

        int mode = Math.round(buf[0]);
        if (currentMode == 1 && mode != 1) {
            releaseMenuKeys();
        }
        currentMode = mode;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

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

        int visualStyle = Math.round(buf[1]);
        float opacity = buf[2];
        float stickFrac = buf[3];
        boolean stickHeld = buf[4] > 0.5f;
        float stickX = buf[5] * w;
        float stickY = buf[6] * h;
        float stickDX = buf[7];
        float stickDY = buf[8];
        float sprintThresh = buf[9];
        boolean autoSprint = buf[10] > 0.5f;
        int btnCount = Math.round(buf[11]);

        if (stickHeld) {
            float stickR = Math.max(0.06f, stickFrac) * h;
            renderer.drawJoystick(canvas, stickX, stickY, stickR, stickDX, stickDY,
                    opacity, autoSprint, sprintThresh, false, false);
        }

        int pos = 12;
        for (int i = 0; i < btnCount && pos + 6 <= n; i++) {
            float cx = buf[pos++] * w;
            float cy = buf[pos++] * h;
            float halfW = buf[pos++] * w;
            float halfH = buf[pos++] * h;
            boolean held = buf[pos++] > 0.5f;
            int styleId = Math.round(buf[pos++]);
            float r = Math.min(halfW, halfH);
            if (r < 10f) {
                r = halfH;
            }
            renderer.drawControl(canvas, cx, cy, r, styleId, visualStyle, opacity, held, false, false);
        }
    }
}
