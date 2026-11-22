package org.bo1port.zombies;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Shared hardware-accelerated anti-aliased HD vector renderer for Black Ops I touch controls.
 * Used identically by both TouchEditorActivity (1:1 fullscreen editor) and TouchHudOverlay (live in-game HUD).
 */
public final class TouchHudRenderer {
    // Style IDs matching native_touch.cpp
    public static final int ST_FIRE = 1;
    public static final int ST_FIRE2 = 2;
    public static final int ST_JUMP = 10;
    public static final int ST_CROUCH = 11;
    public static final int ST_USE = 12;
    public static final int ST_SWAP = 13;
    public static final int ST_AIM = 14;
    public static final int ST_KNIFE = 15;
    public static final int ST_NADE = 16;
    public static final int ST_TAC = 17;
    public static final int ST_PAUSE = 18;
    public static final int ST_SCORES = 19;
    public static final int ST_UP = 20;
    public static final int ST_DOWN = 21;
    public static final int ST_LEFT = 22;
    public static final int ST_RIGHT = 23;

    public static final int VISUAL_CODM = 0;
    public static final int VISUAL_XBOX = 1;
    public static final int VISUAL_PS = 2;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path();

    public TouchHudRenderer() {
        fillPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconFillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    public static String spanishCaption(int styleId) {
        switch (styleId) {
            case ST_FIRE: return "DISPARAR";
            case ST_FIRE2: return "DISPARO IZQ.";
            case ST_AIM: return "APUNTAR";
            case ST_JUMP: return "SALTAR";
            case ST_CROUCH: return "AGACHAR";
            case ST_USE: return "USAR";
            case ST_SWAP: return "ARMA";
            case ST_KNIFE: return "GOLPE";
            case ST_NADE: return "GRANADA";
            case ST_TAC: return "TÁCTICA";
            case ST_PAUSE: return "PAUSA";
            case ST_SCORES: return "MARCADOR";
            default: return "";
        }
    }

    public void drawControl(Canvas c, float cx, float cy, float r, int styleId, int visualStyle,
                            float opacity, boolean held, boolean selected, boolean hiddenPreview) {
        float baseAlpha = hiddenPreview ? 0.22f : Math.max(0.10f, Math.min(1.0f, opacity));
        float strokeAlpha = Math.min(1.0f, baseAlpha * 1.85f);
        float iconAlpha = Math.min(1.0f, baseAlpha * 2.1f);

        int bgColor = held ? 0xFFF5921E : 0xFF0E141C;
        int ringColor = held ? 0xFFFFC168 : 0xFFD6E0EC;
        int glyphColor = held ? 0xFFFFFFFF : 0xFFEAF1F8;

        // Style-specific ring & glyph tints for Xbox / PlayStation
        if (!held) {
            if (visualStyle == VISUAL_XBOX) {
                if (styleId == ST_JUMP) { ringColor = 0xFF38DB57; glyphColor = 0xFF59F576; }
                else if (styleId == ST_CROUCH) { ringColor = 0xFFEF4444; glyphColor = 0xFFFF6B6B; }
                else if (styleId == ST_USE) { ringColor = 0xFF3B82F6; glyphColor = 0xFF60A5FA; }
                else if (styleId == ST_SWAP) { ringColor = 0xFFFACC15; glyphColor = 0xFFFDE047; }
                else if (styleId == ST_FIRE || styleId == ST_FIRE2) { ringColor = 0xFFF5921E; }
                else if (styleId == ST_AIM) { ringColor = 0xFFFBBF24; }
            } else if (visualStyle == VISUAL_PS) {
                if (styleId == ST_JUMP) { ringColor = 0xFF60A5FA; glyphColor = 0xFF7DD3FC; }
                else if (styleId == ST_CROUCH) { ringColor = 0xFFF87171; glyphColor = 0xFFFCA5A5; }
                else if (styleId == ST_USE) { ringColor = 0xFFF472B6; glyphColor = 0xFFF9A8D4; }
                else if (styleId == ST_SWAP) { ringColor = 0xFF34D399; glyphColor = 0xFF6EE7B7; }
                else if (styleId == ST_FIRE || styleId == ST_FIRE2) { ringColor = 0xFFF5921E; }
                else if (styleId == ST_AIM) { ringColor = 0xFFFBBF24; }
            } else {
                // CODM / Shooter HD
                if (styleId == ST_FIRE || styleId == ST_FIRE2) {
                    ringColor = 0xFFE8EFF7;
                }
            }
        }

        // Translucent dark glass disc
        fillPaint.setColor(bgColor);
        fillPaint.setAlpha(Math.round((held ? 0.55f : baseAlpha * 0.65f) * 255f));
        c.drawCircle(cx, cy, r, fillPaint);

        // Outer crisp ring
        float strokeW = Math.max(2.0f, r * 0.055f);
        ringPaint.setStrokeWidth(strokeW);
        ringPaint.setColor(selected ? 0xFFF5921E : ringColor);
        ringPaint.setAlpha(Math.round((selected ? 1.0f : strokeAlpha) * 255f));
        c.drawCircle(cx, cy, r - strokeW * 0.5f, ringPaint);

        // Double tactical outer arc on primary/secondary FIRE (CODM signature look)
        if ((styleId == ST_FIRE || styleId == ST_FIRE2) && visualStyle == VISUAL_CODM) {
            float outerR = r * 1.13f;
            oval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
            accentPaint.setStrokeWidth(Math.max(2.2f, r * 0.05f));
            accentPaint.setColor(held ? 0xFFF5921E : 0xFFE2EBF5);
            accentPaint.setAlpha(Math.round(strokeAlpha * 0.85f * 255f));
            c.drawArc(oval, -52f, 104f, false, accentPaint);
            c.drawArc(oval, 128f, 104f, false, accentPaint);
        }

        // Selection halo in fullscreen editor
        if (selected) {
            accentPaint.setStrokeWidth(Math.max(3.0f, r * 0.07f));
            accentPaint.setColor(0xFFF5921E);
            accentPaint.setAlpha(235);
            c.drawCircle(cx, cy, r * 1.18f, accentPaint);
        }

        int iAlpha = Math.round(iconAlpha * 255f);
        iconPaint.setColor(glyphColor);
        iconPaint.setAlpha(iAlpha);
        iconPaint.setStrokeWidth(Math.max(2.2f, r * 0.085f));
        iconFillPaint.setColor(glyphColor);
        iconFillPaint.setAlpha(iAlpha);
        textPaint.setColor(glyphColor);
        textPaint.setAlpha(iAlpha);

        if (visualStyle == VISUAL_XBOX) {
            drawXboxGlyph(c, cx, cy, r, styleId);
        } else if (visualStyle == VISUAL_PS) {
            drawPsGlyph(c, cx, cy, r, styleId);
        } else {
            drawCodmIcon(c, cx, cy, r, styleId);
        }

        // Subtle tactical caption underneath (CODM look)
        String cap = spanishCaption(styleId);
        if (visualStyle == VISUAL_CODM && !cap.isEmpty() && r >= 22f) {
            labelPaint.setColor(selected ? 0xFFF5921E : 0xFFD8E2EE);
            labelPaint.setAlpha(Math.round(Math.min(1.0f, strokeAlpha * 0.92f) * 255f));
            labelPaint.setTextSize(Math.max(15f, Math.min(26f, r * 0.30f)));
            c.drawText(cap, cx, cy + r + labelPaint.getTextSize() * 1.05f, labelPaint);
        }
    }

    public void drawJoystick(Canvas c, float cx, float cy, float r, float dx, float dy,
                             float opacity, boolean autoSprint, float sprintThresh, boolean showCaption) {
        float baseAlpha = Math.max(0.12f, Math.min(1.0f, opacity));
        float strokeAlpha = Math.min(1.0f, baseAlpha * 1.75f);
        float mag = (float) Math.hypot(dx, dy);
        boolean sprinting = autoSprint && mag >= sprintThresh && dy > 0.45f;

        // Outer translucent disc
        fillPaint.setColor(0xFF0E141C);
        fillPaint.setAlpha(Math.round(baseAlpha * 0.48f * 255f));
        c.drawCircle(cx, cy, r, fillPaint);

        // Outer crisp ring
        ringPaint.setStrokeWidth(Math.max(2.2f, r * 0.025f));
        ringPaint.setColor(sprinting ? 0xFFF5921E : 0xFFD6E0EC);
        ringPaint.setAlpha(Math.round(strokeAlpha * 255f));
        c.drawCircle(cx, cy, r, ringPaint);

        // Sprint arc + double chevron (^) at top of joystick
        if (autoSprint) {
            float arcR = r * 0.84f;
            oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
            accentPaint.setStrokeWidth(Math.max(2.5f, r * 0.03f));
            accentPaint.setColor(sprinting ? 0xFFF5921E : 0xFFC5D2E0);
            accentPaint.setAlpha(Math.round((sprinting ? 1.0f : strokeAlpha * 0.75f) * 255f));
            c.drawArc(oval, -125f, 70f, false, accentPaint);

            float chevY = cy - r * 0.62f;
            float chevW = r * 0.13f;
            iconPaint.setColor(sprinting ? 0xFFF5921E : 0xFFD6E0EC);
            iconPaint.setAlpha(Math.round((sprinting ? 1.0f : strokeAlpha * 0.85f) * 255f));
            iconPaint.setStrokeWidth(Math.max(2.2f, r * 0.028f));
            for (int k = 0; k < 2; k++) {
                float yOff = chevY - k * r * 0.095f;
                path.reset();
                path.moveTo(cx - chevW, yOff + chevW * 0.65f);
                path.lineTo(cx, yOff);
                path.lineTo(cx + chevW, yOff + chevW * 0.65f);
                c.drawPath(path, iconPaint);
            }
        }

        // Thumb knob
        float knobX = cx + dx * r * 0.72f;
        float knobY = cy - dy * r * 0.72f;
        float knobR = r * 0.34f;
        fillPaint.setColor(sprinting ? 0xFFF5921E : 0xFF9FB0C4);
        fillPaint.setAlpha(Math.round(Math.min(1.0f, baseAlpha * 0.85f) * 255f));
        c.drawCircle(knobX, knobY, knobR, fillPaint);

        ringPaint.setStrokeWidth(Math.max(2.0f, r * 0.025f));
        ringPaint.setColor(0xFFFFFFFF);
        ringPaint.setAlpha(Math.round(strokeAlpha * 255f));
        c.drawCircle(knobX, knobY, knobR, ringPaint);

        // Center dot
        iconFillPaint.setColor(0xFFFFFFFF);
        iconFillPaint.setAlpha(Math.round(strokeAlpha * 255f));
        c.drawCircle(knobX, knobY, Math.max(3f, r * 0.035f), iconFillPaint);

        if (showCaption) {
            labelPaint.setColor(0xFFD8E2EE);
            labelPaint.setAlpha(Math.round(strokeAlpha * 0.9f * 255f));
            labelPaint.setTextSize(Math.max(16f, r * 0.16f));
            c.drawText("MOVER", cx, cy + r + labelPaint.getTextSize() * 1.15f, labelPaint);
        }
    }

    public void drawMenuButton(Canvas c, float cx, float cy, float r, String symbol, String caption,
                               boolean held, boolean primary) {
        fillPaint.setColor(held ? 0xFFF5921E : (primary ? 0xFF2B2114 : 0xFF0E141C));
        fillPaint.setAlpha(held ? 215 : 175);
        c.drawCircle(cx, cy, r, fillPaint);

        ringPaint.setStrokeWidth(Math.max(2.5f, r * 0.065f));
        ringPaint.setColor(held ? 0xFFFFD08A : (primary ? 0xFFF5921E : 0xFFD6E0EC));
        ringPaint.setAlpha(230);
        c.drawCircle(cx, cy, r - ringPaint.getStrokeWidth() * 0.5f, ringPaint);

        textPaint.setColor(held ? 0xFF0B0D10 : (primary ? 0xFFF5921E : 0xFFFFFFFF));
        textPaint.setAlpha(245);
        textPaint.setTextSize(r * (symbol.length() > 1 ? 0.52f : 0.78f));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(symbol, cx, baseline, textPaint);

        if (caption != null && !caption.isEmpty()) {
            labelPaint.setColor(primary ? 0xFFF5921E : 0xFFD8E2EE);
            labelPaint.setAlpha(220);
            labelPaint.setTextSize(Math.max(16f, r * 0.30f));
            c.drawText(caption, cx, cy + r + labelPaint.getTextSize() * 1.1f, labelPaint);
        }
    }

    private void drawCenteredText(Canvas c, float cx, float cy, float r, String text) {
        float factor = text.length() <= 1 ? 0.88f : (text.length() == 2 ? 0.64f : 0.44f);
        textPaint.setTextSize(r * factor);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(text, cx, baseline, textPaint);
    }

    private void drawXboxGlyph(Canvas c, float cx, float cy, float r, int styleId) {
        switch (styleId) {
            case ST_FIRE: drawCenteredText(c, cx, cy, r, "RT"); break;
            case ST_FIRE2: drawCenteredText(c, cx, cy, r, "RT"); break;
            case ST_AIM: drawCenteredText(c, cx, cy, r, "LT"); break;
            case ST_JUMP: drawCenteredText(c, cx, cy, r, "A"); break;
            case ST_CROUCH: drawCenteredText(c, cx, cy, r, "B"); break;
            case ST_USE: drawCenteredText(c, cx, cy, r, "X"); break;
            case ST_SWAP: drawCenteredText(c, cx, cy, r, "Y"); break;
            case ST_KNIFE: drawCenteredText(c, cx, cy, r, "R3"); break;
            case ST_NADE: drawCenteredText(c, cx, cy, r, "RB"); break;
            case ST_TAC: drawCenteredText(c, cx, cy, r, "LB"); break;
            case ST_PAUSE: drawCenteredText(c, cx, cy, r, "MENU"); break;
            case ST_SCORES: drawCenteredText(c, cx, cy, r, "VIEW"); break;
            case ST_UP: drawChevron(c, cx, cy, r * 0.42f, 0); break;
            case ST_DOWN: drawChevron(c, cx, cy, r * 0.42f, 180); break;
            case ST_LEFT: drawChevron(c, cx, cy, r * 0.42f, 270); break;
            case ST_RIGHT: drawChevron(c, cx, cy, r * 0.42f, 90); break;
            default: break;
        }
    }

    private void drawPsGlyph(Canvas c, float cx, float cy, float r, int styleId) {
        float g = r * 0.44f;
        switch (styleId) {
            case ST_JUMP: // Cross
                iconPaint.setStrokeWidth(Math.max(3.0f, r * 0.11f));
                c.drawLine(cx - g, cy - g, cx + g, cy + g, iconPaint);
                c.drawLine(cx - g, cy + g, cx + g, cy - g, iconPaint);
                break;
            case ST_CROUCH: // Circle
                iconPaint.setStrokeWidth(Math.max(3.0f, r * 0.11f));
                c.drawCircle(cx, cy, g * 1.05f, iconPaint);
                break;
            case ST_USE: // Square
                iconPaint.setStrokeWidth(Math.max(3.0f, r * 0.11f));
                c.drawRect(cx - g * 0.92f, cy - g * 0.92f, cx + g * 0.92f, cy + g * 0.92f, iconPaint);
                break;
            case ST_SWAP: // Triangle
                iconPaint.setStrokeWidth(Math.max(3.0f, r * 0.11f));
                path.reset();
                path.moveTo(cx, cy - g * 1.05f);
                path.lineTo(cx + g * 1.05f, cy + g * 0.85f);
                path.lineTo(cx - g * 1.05f, cy + g * 0.85f);
                path.close();
                c.drawPath(path, iconPaint);
                break;
            case ST_FIRE:
            case ST_FIRE2: drawCenteredText(c, cx, cy, r, "R2"); break;
            case ST_AIM: drawCenteredText(c, cx, cy, r, "L2"); break;
            case ST_KNIFE: drawCenteredText(c, cx, cy, r, "R3"); break;
            case ST_NADE: drawCenteredText(c, cx, cy, r, "R1"); break;
            case ST_TAC: drawCenteredText(c, cx, cy, r, "L1"); break;
            case ST_PAUSE: drawCenteredText(c, cx, cy, r, "OPT"); break;
            case ST_SCORES: drawCenteredText(c, cx, cy, r, "MAP"); break;
            case ST_UP: drawChevron(c, cx, cy, r * 0.42f, 0); break;
            case ST_DOWN: drawChevron(c, cx, cy, r * 0.42f, 180); break;
            case ST_LEFT: drawChevron(c, cx, cy, r * 0.42f, 270); break;
            case ST_RIGHT: drawChevron(c, cx, cy, r * 0.42f, 90); break;
            default: break;
        }
    }

    private void drawCodmIcon(Canvas c, float cx, float cy, float r, int styleId) {
        switch (styleId) {
            case ST_FIRE:
            case ST_FIRE2:
                drawBulletIcon(c, cx, cy, r * 0.56f);
                break;
            case ST_AIM:
                drawCrosshairIcon(c, cx, cy, r * 0.54f);
                break;
            case ST_JUMP:
                drawJumpIcon(c, cx, cy, r * 0.50f);
                break;
            case ST_CROUCH:
                drawCrouchIcon(c, cx, cy, r * 0.50f);
                break;
            case ST_USE:
                drawHandIcon(c, cx, cy, r * 0.48f);
                break;
            case ST_SWAP:
                drawSwapIcon(c, cx, cy, r * 0.50f);
                break;
            case ST_KNIFE:
                drawKnifeIcon(c, cx, cy, r * 0.54f);
                break;
            case ST_NADE:
                drawFragIcon(c, cx, cy, r * 0.48f);
                break;
            case ST_TAC:
                drawTacIcon(c, cx, cy, r * 0.48f);
                break;
            case ST_PAUSE:
                float bw = r * 0.16f, bh = r * 0.44f, gap = r * 0.16f;
                c.drawRoundRect(cx - gap - bw, cy - bh, cx - gap, cy + bh, bw * 0.35f, bw * 0.35f, iconFillPaint);
                c.drawRoundRect(cx + gap, cy - bh, cx + gap + bw, cy + bh, bw * 0.35f, bw * 0.35f, iconFillPaint);
                break;
            case ST_SCORES:
                drawScoreIcon(c, cx, cy, r * 0.48f);
                break;
            case ST_UP: drawChevron(c, cx, cy, r * 0.42f, 0); break;
            case ST_DOWN: drawChevron(c, cx, cy, r * 0.42f, 180); break;
            case ST_LEFT: drawChevron(c, cx, cy, r * 0.42f, 270); break;
            case ST_RIGHT: drawChevron(c, cx, cy, r * 0.42f, 90); break;
            default: break;
        }
    }

    private void drawChevron(Canvas c, float cx, float cy, float s, float deg) {
        c.save();
        c.rotate(deg, cx, cy);
        path.reset();
        path.moveTo(cx - s, cy + s * 0.45f);
        path.lineTo(cx, cy - s * 0.55f);
        path.lineTo(cx + s, cy + s * 0.45f);
        c.drawPath(path, iconPaint);
        c.restore();
    }

    private void drawBulletIcon(Canvas c, float cx, float cy, float s) {
        c.save();
        c.rotate(42f, cx, cy);
        // Bullet body + pointed tip
        path.reset();
        path.moveTo(cx, cy - s * 0.95f);
        path.quadTo(cx + s * 0.34f, cy - s * 0.35f, cx + s * 0.34f, cy + s * 0.45f);
        path.lineTo(cx - s * 0.34f, cy + s * 0.45f);
        path.quadTo(cx - s * 0.34f, cy - s * 0.35f, cx, cy - s * 0.95f);
        path.close();
        c.drawPath(path, iconFillPaint);
        // Rim base
        c.drawRoundRect(cx - s * 0.38f, cy + s * 0.58f, cx + s * 0.38f, cy + s * 0.85f, s * 0.08f, s * 0.08f, iconFillPaint);
        c.restore();
    }

    private void drawCrosshairIcon(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, s * 0.68f, iconPaint);
        c.drawLine(cx, cy - s, cx, cy - s * 0.35f, iconPaint);
        c.drawLine(cx, cy + s * 0.35f, cx, cy + s, iconPaint);
        c.drawLine(cx - s, cy, cx - s * 0.35f, cy, iconPaint);
        c.drawLine(cx + s * 0.35f, cy, cx + s, cy, iconPaint);
        c.drawCircle(cx, cy, Math.max(2.5f, s * 0.14f), iconFillPaint);
    }

    private void drawJumpIcon(Canvas c, float cx, float cy, float s) {
        // Head
        c.drawCircle(cx, cy - s * 0.62f, s * 0.22f, iconFillPaint);
        // Torso
        c.drawLine(cx, cy - s * 0.36f, cx, cy + s * 0.20f, iconPaint);
        // Arms up
        c.drawLine(cx, cy - s * 0.18f, cx - s * 0.55f, cy - s * 0.50f, iconPaint);
        c.drawLine(cx, cy - s * 0.18f, cx + s * 0.55f, cy - s * 0.50f, iconPaint);
        // Legs bent in jump
        c.drawLine(cx, cy + s * 0.20f, cx - s * 0.42f, cy + s * 0.72f, iconPaint);
        c.drawLine(cx, cy + s * 0.20f, cx + s * 0.48f, cy + s * 0.55f, iconPaint);
    }

    private void drawCrouchIcon(Canvas c, float cx, float cy, float s) {
        // Head
        c.drawCircle(cx - s * 0.18f, cy - s * 0.45f, s * 0.22f, iconFillPaint);
        // Crouched torso + knee + rifle line
        c.drawLine(cx - s * 0.18f, cy - s * 0.20f, cx - s * 0.35f, cy + s * 0.25f, iconPaint);
        c.drawLine(cx - s * 0.35f, cy + s * 0.25f, cx + s * 0.25f, cy + s * 0.25f, iconPaint);
        c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.25f, cy + s * 0.72f, iconPaint);
        c.drawLine(cx - s * 0.35f, cy + s * 0.25f, cx - s * 0.55f, cy + s * 0.72f, iconPaint);
        c.drawLine(cx - s * 0.10f, cy - s * 0.05f, cx + s * 0.72f, cy - s * 0.05f, iconPaint);
    }

    private void drawHandIcon(Canvas c, float cx, float cy, float s) {
        // Palm
        oval.set(cx - s * 0.46f, cy - s * 0.05f, cx + s * 0.46f, cy + s * 0.72f);
        c.drawRoundRect(oval, s * 0.22f, s * 0.22f, iconPaint);
        // 4 fingers
        float[] fx = { -0.34f, -0.11f, 0.12f, 0.35f };
        float[] ft = { -0.58f, -0.74f, -0.70f, -0.48f };
        for (int i = 0; i < 4; i++) {
            c.drawLine(cx + s * fx[i], cy - s * 0.05f, cx + s * fx[i], cy + s * ft[i], iconPaint);
        }
        // Thumb
        c.drawLine(cx - s * 0.46f, cy + s * 0.32f, cx - s * 0.72f, cy + s * 0.02f, iconPaint);
    }

    private void drawSwapIcon(Canvas c, float cx, float cy, float s) {
        // Top arrow right
        c.drawLine(cx - s * 0.65f, cy - s * 0.32f, cx + s * 0.58f, cy - s * 0.32f, iconPaint);
        path.reset();
        path.moveTo(cx + s * 0.25f, cy - s * 0.62f);
        path.lineTo(cx + s * 0.65f, cy - s * 0.32f);
        path.lineTo(cx + s * 0.25f, cy - s * 0.02f);
        c.drawPath(path, iconPaint);
        // Bottom arrow left
        c.drawLine(cx + s * 0.65f, cy + s * 0.32f, cx - s * 0.58f, cy + s * 0.32f, iconPaint);
        path.reset();
        path.moveTo(cx - s * 0.25f, cy + s * 0.02f);
        path.lineTo(cx - s * 0.65f, cy + s * 0.32f);
        path.lineTo(cx - s * 0.25f, cy + s * 0.62f);
        c.drawPath(path, iconPaint);
    }

    private void drawKnifeIcon(Canvas c, float cx, float cy, float s) {
        c.save();
        c.rotate(45f, cx, cy);
        // Blade
        path.reset();
        path.moveTo(cx, cy - s * 0.92f);
        path.lineTo(cx + s * 0.24f, cy + s * 0.15f);
        path.lineTo(cx - s * 0.20f, cy + s * 0.15f);
        path.close();
        c.drawPath(path, iconFillPaint);
        // Guard + handle
        c.drawLine(cx - s * 0.38f, cy + s * 0.20f, cx + s * 0.38f, cy + s * 0.20f, iconPaint);
        c.drawRoundRect(cx - s * 0.14f, cy + s * 0.25f, cx + s * 0.14f, cy + s * 0.85f, s * 0.06f, s * 0.06f, iconFillPaint);
        c.restore();
    }

    private void drawFragIcon(Canvas c, float cx, float cy, float s) {
        oval.set(cx - s * 0.52f, cy - s * 0.25f, cx + s * 0.52f, cy + s * 0.78f);
        c.drawOval(oval, iconFillPaint);
        c.drawRect(cx - s * 0.20f, cy - s * 0.62f, cx + s * 0.20f, cy - s * 0.22f, iconFillPaint);
        c.drawLine(cx + s * 0.20f, cy - s * 0.52f, cx + s * 0.55f, cy - s * 0.25f, iconPaint);
    }

    private void drawTacIcon(Canvas c, float cx, float cy, float s) {
        oval.set(cx - s * 0.36f, cy - s * 0.30f, cx + s * 0.36f, cy + s * 0.76f);
        c.drawRoundRect(oval, s * 0.14f, s * 0.14f, iconPaint);
        c.drawLine(cx - s * 0.36f, cy + s * 0.05f, cx + s * 0.36f, cy + s * 0.05f, iconPaint);
        c.drawLine(cx - s * 0.36f, cy + s * 0.38f, cx + s * 0.36f, cy + s * 0.38f, iconPaint);
        c.drawRect(cx - s * 0.20f, cy - s * 0.68f, cx + s * 0.20f, cy - s * 0.30f, iconFillPaint);
    }

    private void drawScoreIcon(Canvas c, float cx, float cy, float s) {
        oval.set(cx - s * 0.72f, cy - s * 0.54f, cx + s * 0.72f, cy + s * 0.54f);
        c.drawRoundRect(oval, s * 0.16f, s * 0.16f, iconPaint);
        c.drawLine(cx - s * 0.42f, cy - s * 0.20f, cx + s * 0.42f, cy - s * 0.20f, iconPaint);
        c.drawLine(cx - s * 0.42f, cy + s * 0.05f, cx + s * 0.42f, cy + s * 0.05f, iconPaint);
        c.drawLine(cx - s * 0.42f, cy + s * 0.30f, cx + s * 0.18f, cy + s * 0.30f, iconPaint);
    }
}
