package org.bo1port.zombies;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 1:1 Fullscreen Immersive Touch Controls Editor for Black Ops I.
 * Supports editable joystick (position & size), independent CORRER button, grouped/ungrouped D-pad,
 * per-button size & visibility, and tactile response settings.
 */
public class TouchEditorActivity extends Activity {
    private static final String PREFS = "bo1_launcher_prefs";

    private static final int SEL_NONE = -1;
    private static final int SEL_STICK = -2;

    private static final int IDX_DPAD_UP = 10;
    private static final int IDX_DPAD_DOWN = 11;
    private static final int IDX_DPAD_LEFT = 12;
    private static final int IDX_DPAD_RIGHT = 13;

    private static final int COL_BG = 0xFF131D27;
    private static final int COL_PANEL = 0xE6141A22;
    private static final int COL_BORDER = 0xFF2E3A4A;
    private static final int COL_AMBER = 0xFFF5921E;
    private static final int COL_TEXT = 0xFFEAECEF;
    private static final int COL_MUTED = 0xFF98A2B3;

    private static final class Ctrl {
        final String name;
        final String title;
        final int styleId;
        float x, y, size;
        boolean visible = true;

        Ctrl(String name, String title, int styleId, float x, float y, float size) {
            this.name = name;
            this.title = title;
            this.styleId = styleId;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    private final Ctrl[] controls = new Ctrl[] {
            new Ctrl("fire", "DISPARAR", TouchHudRenderer.ST_FIRE, 0.845f, 0.600f, 0.085f),
            new Ctrl("fire2", "DISPARO IZQ.", TouchHudRenderer.ST_FIRE2, 0.065f, 0.540f, 0.055f),
            new Ctrl("aim", "APUNTAR", TouchHudRenderer.ST_AIM, 0.720f, 0.760f, 0.065f),
            new Ctrl("jump", "SALTAR", TouchHudRenderer.ST_JUMP, 0.955f, 0.650f, 0.060f),
            new Ctrl("crouch", "AGACHAR", TouchHudRenderer.ST_CROUCH, 0.955f, 0.430f, 0.052f),
            new Ctrl("use", "USAR / REC.", TouchHudRenderer.ST_USE, 0.800f, 0.380f, 0.060f),
            new Ctrl("swap", "ARMA", TouchHudRenderer.ST_SWAP, 0.885f, 0.230f, 0.052f),
            new Ctrl("knife", "GOLPE", TouchHudRenderer.ST_KNIFE, 0.735f, 0.540f, 0.052f),
            new Ctrl("nade", "GRANADA", TouchHudRenderer.ST_NADE, 0.630f, 0.900f, 0.050f),
            new Ctrl("tac", "TÁCTICA", TouchHudRenderer.ST_TAC, 0.535f, 0.900f, 0.050f),
            new Ctrl("up", "CRUCETA ↑", TouchHudRenderer.ST_UP, 0.100f, 0.200f, 0.034f),
            new Ctrl("down", "CRUCETA ↓", TouchHudRenderer.ST_DOWN, 0.100f, 0.350f, 0.034f),
            new Ctrl("left", "CRUCETA ←", TouchHudRenderer.ST_LEFT, 0.055f, 0.275f, 0.034f),
            new Ctrl("right", "CRUCETA →", TouchHudRenderer.ST_RIGHT, 0.145f, 0.275f, 0.034f),
            new Ctrl("pause", "PAUSA", TouchHudRenderer.ST_PAUSE, 0.965f, 0.065f, 0.040f),
            new Ctrl("scores", "MARCADOR", TouchHudRenderer.ST_SCORES, 0.890f, 0.065f, 0.040f),
            new Ctrl("sprint", "CORRER", TouchHudRenderer.ST_SPRINT, 0.250f, 0.560f, 0.052f),
    };

    // Presets matching native_touch.cpp (0=CODM/Shooter, 1=Claw 4F, 2=Gamepad)
    private static final float[][][] PRESETS = new float[][][] {
            // 0: CODM / Shooter 2-finger
            {
                    {0.845f, 0.600f, 0.085f}, {0.065f, 0.540f, 0.055f}, {0.720f, 0.760f, 0.065f},
                    {0.955f, 0.650f, 0.060f}, {0.955f, 0.430f, 0.052f}, {0.800f, 0.380f, 0.060f},
                    {0.885f, 0.230f, 0.052f}, {0.735f, 0.540f, 0.052f}, {0.630f, 0.900f, 0.050f},
                    {0.535f, 0.900f, 0.050f}, {0.100f, 0.200f, 0.034f}, {0.100f, 0.350f, 0.034f},
                    {0.055f, 0.275f, 0.034f}, {0.145f, 0.275f, 0.034f}, {0.965f, 0.065f, 0.040f},
                    {0.890f, 0.065f, 0.040f}, {0.250f, 0.560f, 0.052f},
            },
            // 1: Claw 4-finger
            {
                    {0.120f, 0.210f, 0.082f}, {0.840f, 0.610f, 0.068f}, {0.880f, 0.210f, 0.072f},
                    {0.945f, 0.720f, 0.058f}, {0.945f, 0.500f, 0.054f}, {0.755f, 0.500f, 0.058f},
                    {0.785f, 0.320f, 0.052f}, {0.680f, 0.690f, 0.052f}, {0.615f, 0.900f, 0.048f},
                    {0.520f, 0.900f, 0.048f}, {0.265f, 0.145f, 0.032f}, {0.265f, 0.275f, 0.032f},
                    {0.220f, 0.210f, 0.032f}, {0.310f, 0.210f, 0.032f}, {0.965f, 0.065f, 0.038f},
                    {0.895f, 0.065f, 0.038f}, {0.250f, 0.560f, 0.050f},
            },
            // 2: Classic Gamepad (Xbox / PlayStation diamond)
            {
                    {0.915f, 0.210f, 0.072f}, {0.085f, 0.440f, 0.054f}, {0.085f, 0.210f, 0.072f},
                    {0.865f, 0.785f, 0.056f}, {0.940f, 0.640f, 0.056f}, {0.790f, 0.640f, 0.056f},
                    {0.865f, 0.495f, 0.056f}, {0.700f, 0.800f, 0.050f}, {0.795f, 0.210f, 0.054f},
                    {0.205f, 0.210f, 0.054f}, {0.115f, 0.625f, 0.036f}, {0.115f, 0.795f, 0.036f},
                    {0.065f, 0.710f, 0.036f}, {0.165f, 0.710f, 0.036f}, {0.555f, 0.075f, 0.040f},
                    {0.445f, 0.075f, 0.040f}, {0.065f, 0.540f, 0.048f},
            }
    };

    private File filesDir;
    private SharedPreferences prefs;
    private EditorCanvas canvasView;

    // Editable joystick state
    private float stickX = 0.155f;
    private float stickY = 0.780f;
    private float stickSize = 0.130f;

    private int touchStyle = 0;       // 0=CODM HD, 1=Xbox, 2=PlayStation
    private int touchPreset = 0;      // 0=CODM, 1=Claw 4D, 2=Gamepad
    private int touchOpacityPct = 45;
    private boolean aimDrag = true;
    private boolean autoSprint = true;
    private int sprintThreshPct = 85;
    private boolean aimOnFire = false;
    private boolean autoFire = true;
    private boolean dpadGrouped = true;

    private int selectedIdx = SEL_NONE;
    private boolean toolbarCollapsed = false;

    private LinearLayout topBar;
    private Button expandBtn;
    private LinearLayout bottomInspector;
    private TextView selTitle;
    private SeekBar selSizeSeek;
    private Button selVisBtn;
    private Button selDpadGroupBtn;
    private Spinner styleSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersive();

        filesDir = getExternalFilesDir(null);
        if (filesDir == null) {
            filesDir = getFilesDir();
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        touchStyle = prefs.getInt("touchStyle", 0);
        touchPreset = prefs.getInt("touchPreset", 0);
        touchOpacityPct = prefs.getInt("touchOpacityPct", 45);
        aimDrag = prefs.getInt("touchAimDrag", 1) != 0;
        autoSprint = prefs.getInt("touchAutoSprint", 1) != 0;
        sprintThreshPct = prefs.getInt("touchSprintThreshPct", 85);
        aimOnFire = prefs.getInt("touchAimOnFire", 0) != 0;
        autoFire = prefs.getInt("touchAutoFire", 1) != 0;
        dpadGrouped = prefs.getInt("touchDpadGrouped", 1) != 0;

        applyPreset(touchPreset);
        loadLayoutFile();
        buildUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable pillBg(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private boolean isDpad(int idx) {
        return idx >= IDX_DPAD_UP && idx <= IDX_DPAD_RIGHT;
    }

    private void snapDpadCross() {
        float cx = (controls[IDX_DPAD_UP].x + controls[IDX_DPAD_DOWN].x
                + controls[IDX_DPAD_LEFT].x + controls[IDX_DPAD_RIGHT].x) * 0.25f;
        float cy = (controls[IDX_DPAD_UP].y + controls[IDX_DPAD_DOWN].y
                + controls[IDX_DPAD_LEFT].y + controls[IDX_DPAD_RIGHT].y) * 0.25f;
        float sz = controls[IDX_DPAD_UP].size;
        float aspect = (canvasView != null && canvasView.getHeight() > 0)
                ? ((float) canvasView.getWidth() / (float) canvasView.getHeight()) : (16f / 9f);
        float dy = sz * 2.25f;
        float dx = dy / aspect;

        controls[IDX_DPAD_UP].x = clamp(cx, 0.03f, 0.97f);
        controls[IDX_DPAD_UP].y = clamp(cy - dy, 0.04f, 0.96f);
        controls[IDX_DPAD_DOWN].x = clamp(cx, 0.03f, 0.97f);
        controls[IDX_DPAD_DOWN].y = clamp(cy + dy, 0.04f, 0.96f);
        controls[IDX_DPAD_LEFT].x = clamp(cx - dx, 0.03f, 0.97f);
        controls[IDX_DPAD_LEFT].y = clamp(cy, 0.04f, 0.96f);
        controls[IDX_DPAD_RIGHT].x = clamp(cx + dx, 0.03f, 0.97f);
        controls[IDX_DPAD_RIGHT].y = clamp(cy, 0.04f, 0.96f);
        for (int i = IDX_DPAD_UP; i <= IDX_DPAD_RIGHT; i++) {
            controls[i].size = sz;
        }
    }

    private void applyPreset(int preset) {
        int idx = Math.max(0, Math.min(PRESETS.length - 1, preset));
        for (int i = 0; i < controls.length; i++) {
            controls[i].x = PRESETS[idx][i][0];
            controls[i].y = PRESETS[idx][i][1];
            controls[i].size = PRESETS[idx][i][2];
            controls[i].visible = true;
        }
        stickX = 0.155f;
        stickY = 0.780f;
        stickSize = 0.130f;
    }

    private File layoutFile() {
        File home = new File(filesDir, "home");
        home.mkdirs();
        return new File(home, "touch_layout.txt");
    }

    private void loadLayoutFile() {
        File f = layoutFile();
        if (!f.isFile()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                String name = parts[0];
                float x = Float.parseFloat(parts[1]);
                float y = Float.parseFloat(parts[2]);
                float sz = Float.parseFloat(parts[3]);
                boolean vis = parts.length < 5 || Integer.parseInt(parts[4]) != 0;
                if ("stick".equals(name)) {
                    stickX = clamp(x, 0.06f, 0.94f);
                    stickY = clamp(y, 0.10f, 0.90f);
                    stickSize = clamp(sz, 0.080f, 0.220f);
                    continue;
                }
                for (Ctrl c : controls) {
                    if (c.name.equals(name)) {
                        c.x = clamp(x, 0.02f, 0.98f);
                        c.y = clamp(y, 0.03f, 0.97f);
                        c.size = clamp(sz, 0.022f, 0.145f);
                        c.visible = vis;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveAndExit() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "stick %.4f %.4f %.4f 1\n", stickX, stickY, stickSize));
        for (Ctrl c : controls) {
            sb.append(String.format(Locale.US, "%s %.4f %.4f %.4f %d\n",
                    c.name, c.x, c.y, c.size, c.visible ? 1 : 0));
        }
        try (FileOutputStream fos = new FileOutputStream(layoutFile())) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "Error al guardar layout: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit()
                .putInt("touchStyle", touchStyle)
                .putInt("touchPreset", touchPreset)
                .putInt("touchOpacityPct", touchOpacityPct)
                .putInt("touchAimDrag", aimDrag ? 1 : 0)
                .putInt("touchAutoSprint", autoSprint ? 1 : 0)
                .putInt("touchSprintThreshPct", sprintThreshPct)
                .putInt("touchAimOnFire", aimOnFire ? 1 : 0)
                .putInt("touchAutoFire", autoFire ? 1 : 0)
                .putInt("touchDpadGrouped", dpadGrouped ? 1 : 0)
                .apply();
        Toast.makeText(this, "Controles guardados (1:1 pantalla completa)", Toast.LENGTH_SHORT).show();
        finish();
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COL_BG);

        // 1:1 Fullscreen interactive canvas
        canvasView = new EditorCanvas(this);
        root.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating top-center compact toolbar
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(pillBg(COL_PANEL, COL_BORDER, 10));
        topBar.setPadding(dp(8), dp(5), dp(8), dp(5));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topLp.topMargin = dp(8);
        topBar.setLayoutParams(topLp);

        final Spinner presetSp = makeCompactSpinner(
                new String[] { "CODM", "Claw 4D", "Xbox / Pad" },
                touchPreset, new AdapterView.OnItemSelectedListener() {
                    private boolean first = true;
                    @Override
                    public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        if (first) { first = false; return; }
                        touchPreset = pos;
                        applyPreset(pos);
                        if (pos == 2 && touchStyle == 0) {
                            touchStyle = 1;
                            if (styleSpinner != null) styleSpinner.setSelection(1);
                        }
                        canvasView.invalidate();
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
        topBar.addView(presetSp);

        styleSpinner = makeCompactSpinner(
                new String[] { "Estilo: CODM", "Estilo: Xbox", "Estilo: PS" },
                touchStyle, new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        touchStyle = pos;
                        canvasView.invalidate();
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
        topBar.addView(styleSpinner);

        topBar.addView(makeBarBtn("Respuesta", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showResponseDialog(); }
        }));
        topBar.addView(makeBarBtn("Restaurar", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyPreset(touchPreset);
                selectControl(SEL_NONE);
                canvasView.invalidate();
            }
        }));
        topBar.addView(makeBarBtn("Cancelar", false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        topBar.addView(makeBarBtn("Guardar", true, new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndExit(); }
        }));
        topBar.addView(makeBarBtn("▲", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setToolbarCollapsed(true); }
        }));
        root.addView(topBar);

        expandBtn = makeBarBtn("⚙ MENÚ", true, new View.OnClickListener() {
            @Override public void onClick(View v) { setToolbarCollapsed(false); }
        });
        FrameLayout.LayoutParams expLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        expLp.topMargin = dp(6);
        expandBtn.setLayoutParams(expLp);
        expandBtn.setVisibility(View.GONE);
        root.addView(expandBtn);

        // Bottom-center selected control inspector pill
        bottomInspector = new LinearLayout(this);
        bottomInspector.setOrientation(LinearLayout.HORIZONTAL);
        bottomInspector.setGravity(Gravity.CENTER_VERTICAL);
        bottomInspector.setBackground(pillBg(COL_PANEL, COL_AMBER, 10));
        bottomInspector.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        botLp.bottomMargin = dp(10);
        bottomInspector.setLayoutParams(botLp);
        bottomInspector.setVisibility(View.GONE);

        selTitle = new TextView(this);
        selTitle.setTextColor(COL_AMBER);
        selTitle.setTypeface(Typeface.DEFAULT_BOLD);
        selTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        selTitle.setPadding(0, 0, dp(10), 0);
        bottomInspector.addView(selTitle);

        TextView szLbl = new TextView(this);
        szLbl.setText("Tamaño");
        szLbl.setTextColor(COL_MUTED);
        szLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bottomInspector.addView(szLbl);

        selSizeSeek = new SeekBar(this);
        selSizeSeek.setMax(140);
        selSizeSeek.setLayoutParams(new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
        selSizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                if (selectedIdx == SEL_STICK) {
                    stickSize = 0.080f + progress * 0.001f;
                    canvasView.invalidate();
                } else if (selectedIdx >= 0) {
                    float newSz = 0.025f + progress * 0.001f;
                    if (dpadGrouped && isDpad(selectedIdx)) {
                        controls[IDX_DPAD_UP].size = newSz;
                        snapDpadCross();
                    } else {
                        controls[selectedIdx].size = newSz;
                    }
                    canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        bottomInspector.addView(selSizeSeek);

        selVisBtn = makeBarBtn("Ocultar", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectedIdx >= 0) {
                    boolean nextVis = !controls[selectedIdx].visible;
                    if (dpadGrouped && isDpad(selectedIdx)) {
                        for (int i = IDX_DPAD_UP; i <= IDX_DPAD_RIGHT; i++) {
                            controls[i].visible = nextVis;
                        }
                    } else {
                        controls[selectedIdx].visible = nextVis;
                    }
                    updateInspector();
                    canvasView.invalidate();
                }
            }
        });
        bottomInspector.addView(selVisBtn);

        selDpadGroupBtn = makeBarBtn("Separar cruceta", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dpadGrouped = !dpadGrouped;
                if (dpadGrouped) {
                    snapDpadCross();
                }
                updateInspector();
                canvasView.invalidate();
            }
        });
        bottomInspector.addView(selDpadGroupBtn);

        root.addView(bottomInspector);
        setContentView(root);
    }

    private void setToolbarCollapsed(boolean collapsed) {
        toolbarCollapsed = collapsed;
        topBar.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        expandBtn.setVisibility(collapsed ? View.VISIBLE : View.GONE);
    }

    private Button makeBarBtn(String text, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF0B0D10 : COL_TEXT);
        b.setBackground(pillBg(primary ? COL_AMBER : 0xFF232C38, primary ? 0 : COL_BORDER, 6));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(30));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private Spinner makeCompactSpinner(String[] items, int initial, AdapterView.OnItemSelectedListener listener) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(COL_TEXT);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                return tv;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setBackgroundColor(0xFF151920);
                tv.setTextColor(COL_TEXT);
                tv.setPadding(dp(10), dp(8), dp(10), dp(8));
                return tv;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(items.length - 1, initial)));
        sp.setBackground(pillBg(0xFF232C38, COL_BORDER, 6));
        sp.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.leftMargin = dp(4);
        sp.setLayoutParams(lp);
        sp.setOnItemSelectedListener(listener);
        return sp;
    }

    private void selectControl(int idx) {
        selectedIdx = idx;
        updateInspector();
    }

    private void updateInspector() {
        if (selectedIdx == SEL_NONE) {
            bottomInspector.setVisibility(View.GONE);
            return;
        }
        bottomInspector.setVisibility(View.VISIBLE);
        if (selectedIdx == SEL_STICK) {
            selTitle.setText("MOVER (JOYSTICK)");
            selSizeSeek.setMax(140); // 0.080 .. 0.220
            selSizeSeek.setProgress(Math.max(0, Math.min(140, Math.round((stickSize - 0.080f) * 1000f))));
            selVisBtn.setVisibility(View.GONE);
            selDpadGroupBtn.setVisibility(View.GONE);
            return;
        }
        Ctrl c = controls[selectedIdx];
        boolean dpad = isDpad(selectedIdx);
        selTitle.setText(dpad && dpadGrouped ? "CRUCETA (BLOQUE)" : c.title);
        selSizeSeek.setMax(115); // 0.025 .. 0.140
        selSizeSeek.setProgress(Math.max(0, Math.min(115, Math.round((c.size - 0.025f) * 1000f))));
        selVisBtn.setVisibility(View.VISIBLE);
        selVisBtn.setText(c.visible ? "Ocultar" : "Mostrar");
        selDpadGroupBtn.setVisibility(dpad ? View.VISIBLE : View.GONE);
        selDpadGroupBtn.setText(dpadGrouped ? "Separar cruceta" : "Agrupar cruceta");
    }

    private void showResponseDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        final CheckBox cbAimDrag = new CheckBox(this);
        cbAimDrag.setText("Arrastrar cámara desde Apuntar");
        cbAimDrag.setChecked(aimDrag);
        box.addView(cbAimDrag);

        final CheckBox cbAutoSprint = new CheckBox(this);
        cbAutoSprint.setText("Correr al empujar el joystick (cualquier dirección)");
        cbAutoSprint.setChecked(autoSprint);
        box.addView(cbAutoSprint);

        final TextView tvThresh = new TextView(this);
        tvThresh.setText("Umbral caminar → correr · " + sprintThreshPct + " %");
        tvThresh.setPadding(dp(6), dp(8), 0, dp(2));
        box.addView(tvThresh);

        final SeekBar sbThresh = new SeekBar(this);
        sbThresh.setMax(48); // 50..98
        sbThresh.setProgress(Math.max(0, Math.min(48, sprintThreshPct - 50)));
        sbThresh.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tvThresh.setText("Umbral caminar → correr · " + (50 + p) + " %");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(sbThresh);

        final CheckBox cbAimFire = new CheckBox(this);
        cbAimFire.setText("Apuntar al disparar (botón principal)");
        cbAimFire.setChecked(aimOnFire);
        box.addView(cbAimFire);

        TextView hint = new TextView(this);
        hint.setText("Mantén Disparar para conservar la mira y el disparo. El disparo izquierdo es de cadera.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(dp(8), 0, dp(8), dp(8));
        box.addView(hint);

        final CheckBox cbAutoFire = new CheckBox(this);
        cbAutoFire.setText("Disparo continuo (armas semiautomáticas)");
        cbAutoFire.setChecked(autoFire);
        box.addView(cbAutoFire);

        new AlertDialog.Builder(this)
                .setTitle("Respuesta táctil")
                .setView(box)
                .setPositiveButton("LISTO", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        aimDrag = cbAimDrag.isChecked();
                        autoSprint = cbAutoSprint.isChecked();
                        sprintThreshPct = 50 + sbThresh.getProgress();
                        aimOnFire = cbAimFire.isChecked();
                        autoFire = cbAutoFire.isChecked();
                        canvasView.invalidate();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private final class EditorCanvas extends View {
        private final TouchHudRenderer renderer = new TouchHudRenderer();
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int dragIdx = SEL_NONE;
        private float grabDX, grabDY;
        private float lastX, lastY;

        EditorCanvas(Context ctx) {
            super(ctx);
            guidePaint.setColor(0x28D6E0EC);
            guidePaint.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // Subtle center crosshair guides (1:1 screen reference)
            c.drawLine(w * 0.5f, 0, w * 0.5f, h, guidePaint);
            c.drawLine(0, h * 0.5f, w, h * 0.5f, guidePaint);

            // Editable Joystick
            float opacity = touchOpacityPct * 0.01f;
            float stickR = stickSize * h;
            renderer.drawJoystick(c, stickX * w, stickY * h, stickR, 0f, 0f,
                    Math.max(0.40f, opacity), autoSprint, sprintThreshPct * 0.01f, true,
                    selectedIdx == SEL_STICK);

            // Draw all controls 1:1
            boolean dpadSelected = dpadGrouped && isDpad(selectedIdx);
            for (int i = 0; i < controls.length; i++) {
                Ctrl ctrl = controls[i];
                float cx = ctrl.x * w;
                float cy = ctrl.y * h;
                float r = ctrl.size * h;
                boolean isSel = (i == selectedIdx) || (dpadSelected && isDpad(i));
                boolean isDrag = (i == dragIdx) || (dpadGrouped && isDpad(dragIdx) && isDpad(i));
                renderer.drawControl(c, cx, cy, r, ctrl.styleId, touchStyle,
                        Math.max(0.45f, opacity), isDrag, isSel, !ctrl.visible);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return false;
            }
            float x = e.getX();
            float y = e.getY();
            int action = e.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                int hit = SEL_NONE;
                float bestR = Float.MAX_VALUE;
                for (int i = 0; i < controls.length; i++) {
                    Ctrl ctrl = controls[i];
                    float cx = ctrl.x * w;
                    float cy = ctrl.y * h;
                    float r = ctrl.size * h * 1.25f;
                    float dx = x - cx, dy = y - cy;
                    if (dx * dx + dy * dy <= r * r && ctrl.size < bestR) {
                        hit = i;
                        bestR = ctrl.size;
                    }
                }
                if (hit == SEL_NONE) {
                    // Check joystick hit
                    float scx = stickX * w;
                    float scy = stickY * h;
                    float sr = stickSize * h * 1.05f;
                    float dx = x - scx, dy = y - scy;
                    if (dx * dx + dy * dy <= sr * sr) {
                        hit = SEL_STICK;
                    }
                }
                if (hit == SEL_STICK) {
                    dragIdx = SEL_STICK;
                    selectControl(SEL_STICK);
                    grabDX = x - stickX * w;
                    grabDY = y - stickY * h;
                } else if (hit >= 0) {
                    dragIdx = hit;
                    selectControl(hit);
                    grabDX = x - controls[hit].x * w;
                    grabDY = y - controls[hit].y * h;
                    lastX = x;
                    lastY = y;
                } else {
                    dragIdx = SEL_NONE;
                    selectControl(SEL_NONE);
                }
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE && dragIdx != SEL_NONE) {
                if (dragIdx == SEL_STICK) {
                    stickX = clamp((x - grabDX) / w, 0.06f, 0.94f);
                    stickY = clamp((y - grabDY) / h, 0.10f, 0.90f);
                } else if (dpadGrouped && isDpad(dragIdx)) {
                    float dxN = (x - lastX) / w;
                    float dyN = (y - lastY) / h;
                    // Keep entire D-pad group inside bounds
                    for (int i = IDX_DPAD_UP; i <= IDX_DPAD_RIGHT; i++) {
                        float nx = controls[i].x + dxN;
                        float ny = controls[i].y + dyN;
                        if (nx < 0.02f) dxN += (0.02f - nx);
                        if (nx > 0.98f) dxN -= (nx - 0.98f);
                        if (ny < 0.03f) dyN += (0.03f - ny);
                        if (ny > 0.97f) dyN -= (ny - 0.97f);
                    }
                    for (int i = IDX_DPAD_UP; i <= IDX_DPAD_RIGHT; i++) {
                        controls[i].x = clamp(controls[i].x + dxN, 0.02f, 0.98f);
                        controls[i].y = clamp(controls[i].y + dyN, 0.03f, 0.97f);
                    }
                    lastX = x;
                    lastY = y;
                } else {
                    controls[dragIdx].x = clamp((x - grabDX) / w, 0.02f, 0.98f);
                    controls[dragIdx].y = clamp((y - grabDY) / h, 0.03f, 0.97f);
                }
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragIdx = SEL_NONE;
                invalidate();
                return true;
            }
            return true;
        }
    }
}
