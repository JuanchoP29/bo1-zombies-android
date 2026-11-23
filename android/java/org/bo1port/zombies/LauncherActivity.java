package org.bo1port.zombies;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pre-launch tactical configuration screen for Black Ops I.
 * Lets the user pick any game folder (validating localization.txt, zone/, main/), import/select custom
 * Vulkan Turnip drivers (.zip), and configure graphics and touch/gamepad controls before launching Bo1Activity.
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PICK_GAME_DIR = 1001;
    private static final int REQ_IMPORT_DRIVER = 1002;
    private static final int REQ_STORAGE_PERM = 1003;

    private static final String PREFS = "bo1_launcher_prefs";

    private static final int COL_BG = 0xFF0B0D10;
    private static final int COL_CARD = 0xFF151920;
    private static final int COL_BORDER = 0xFF28303D;
    private static final int COL_AMBER = 0xFFF5921E;
    private static final int COL_TEXT = 0xFFEAECEF;
    private static final int COL_MUTED = 0xFF98A2B3;
    private static final int COL_OK = 0xFF2ECC71;
    private static final int COL_ERR = 0xFFE74C3C;

    private File filesDir;
    private SharedPreferences prefs;

    private int currentTab = 0;
    private final Button[] tabButtons = new Button[4];
    private LinearLayout tabContent;

    // Live UI references for refresh
    private TextView gamePathView;
    private TextView gameStatusView;
    private LinearLayout driverListBox;
    private LinearLayout savesListBox;

    // Settings state
    private int modeZombies = 1;       // 1 = Zombies, 0 = Multiplayer
    private int renderer = 1;          // 1 = Vulkan, 0 = OpenGL ES
    private int gfxProfile = 0;        // 0=Mobile Optimized (Recommended), 1=High / PC Original, 2=Low / Max FPS
    private int resIndex = 0;          // 0=Keep, 1=1200x540, 2=1600x720, 3=1800x810, 4=2000x900, 5=Native
    private int fpsIndex = 0;          // 0=60, 1=90, 2=120, 3=Unlimited
    private int vsync = 0;             // 0=Off, 1=On
    private int shadows = 1;           // 1=On, 0=Off
    private int shaderWarming = 1;     // 1=On, 0=Off
    private int vkAsync = 1;           // 0=Wait, 1=Effects bg, 2=All bg
    private int fpsCounter = 3;        // 0=None, 1=Simple, 2=Advanced, 3=Full
    private int bypassCharging = 0;    // 0=Off (default), 1=Experimental Root Bypass Charging

    private int touchEnabled = 1;
    private int touchStyle = 0;        // 0=Shooter, 1=Xbox, 2=PlayStation
    private int touchPreset = 0;       // 0=Shooter 2F, 1=Claw 4F, 2=Gamepad
    private int touchSensPct = 12;     // 4..30 -> 0.4..3.0
    private int touchOpacityPct = 45;  // 10..100 -> 0.10..1.00
    private int padDeadzonePct = 10;   // 5..25 -> 0.05..0.25
    private int padRumble = 1;         // 1=On (rate-limited safe), 0=Off

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        filesDir = getExternalFilesDir(null);
        if (filesDir == null) {
            filesDir = getFilesDir();
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        if (currentTab == 0) {
            refreshGameStatus();
            refreshSavesList();
        } else if (currentTab == 1) {
            refreshDriverList();
        } else if (currentTab == 3) {
            selectTab(3);
        }
    }

    private void loadSettings() {
        modeZombies = prefs.getInt("modeZombies", 1);
        renderer = prefs.getInt("renderer", 1);
        gfxProfile = prefs.getInt("gfxProfile", 0);
        resIndex = prefs.getInt("resIndex", 0);
        fpsIndex = prefs.getInt("fpsIndex", 0);
        vsync = prefs.getInt("vsync", 0);
        shadows = prefs.getInt("shadows", 1);
        shaderWarming = prefs.getInt("shaderWarming", 1);
        vkAsync = prefs.getInt("vkAsync", 1);
        fpsCounter = prefs.getInt("fpsCounter", 3);
        bypassCharging = prefs.getInt("bypassCharging", 0);

        touchEnabled = prefs.getInt("touchEnabled", 1);
        touchStyle = prefs.getInt("touchStyle", 0);
        touchPreset = prefs.getInt("touchPreset", 0);
        touchSensPct = prefs.getInt("touchSensPct", 12);
        touchOpacityPct = prefs.getInt("touchOpacityPct", 45);
        padDeadzonePct = prefs.getInt("padDeadzonePct", 10);
        padRumble = prefs.getInt("padRumble", 1);
    }

    private void saveSettings() {
        prefs.edit()
                .putInt("modeZombies", modeZombies)
                .putInt("renderer", renderer)
                .putInt("gfxProfile", gfxProfile)
                .putInt("resIndex", resIndex)
                .putInt("fpsIndex", fpsIndex)
                .putInt("vsync", vsync)
                .putInt("shadows", shadows)
                .putInt("shaderWarming", shaderWarming)
                .putInt("vkAsync", vkAsync)
                .putInt("fpsCounter", fpsCounter)
                .putInt("bypassCharging", bypassCharging)
                .putInt("touchEnabled", touchEnabled)
                .putInt("touchStyle", touchStyle)
                .putInt("touchPreset", touchPreset)
                .putInt("touchSensPct", touchSensPct)
                .putInt("touchOpacityPct", touchOpacityPct)
                .putInt("padDeadzonePct", padDeadzonePct)
                .putInt("padRumble", padRumble)
                .apply();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable boxBg(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.setBackground(boxBg(COL_CARD, COL_BORDER, 10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.setLayoutParams(titleLp);

        TextView title = new TextView(this);
        title.setText("BLACK OPS I");
        title.setTextColor(COL_AMBER);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Android Native Port  •  Zombies & Multiplayer");
        sub.setTextColor(COL_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(sub);
        header.addView(titleCol);

        Button playBtn = new Button(this);
        playBtn.setText("▶  JUGAR");
        playBtn.setTextColor(0xFF0B0D10);
        playBtn.setTypeface(Typeface.DEFAULT_BOLD);
        playBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        playBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        playBtn.setBackground(boxBg(COL_AMBER, 0, 8));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGame();
            }
        });
        header.addView(playBtn);
        root.addView(header);

        // Tabs
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbLp.topMargin = dp(10);
        tbLp.bottomMargin = dp(8);
        tabBar.setLayoutParams(tbLp);

        String[] names = { "JUEGO", "DRIVERS", "GRÁFICOS", "CONTROLES" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = dp(6);
            }
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            tabButtons[i] = b;
            tabBar.addView(b);
        }
        root.addView(tabBar);

        // Scrollable content area
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabContent = new LinearLayout(this);
        tabContent.setOrientation(LinearLayout.VERTICAL);
        tabContent.setPadding(0, 0, 0, dp(16));
        scroll.addView(tabContent);
        root.addView(scroll);

        setContentView(root);
        selectTab(0);
    }

    private void selectTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabButtons.length; i++) {
            boolean active = (i == index);
            tabButtons[i].setBackground(boxBg(active ? COL_AMBER : COL_CARD, active ? 0 : COL_BORDER, 8));
            tabButtons[i].setTextColor(active ? 0xFF0B0D10 : COL_TEXT);
        }
        tabContent.removeAllViews();
        if (index == 0) {
            buildGameTab();
        } else if (index == 1) {
            buildDriversTab();
        } else if (index == 2) {
            buildGraphicsTab();
        } else {
            buildControlsTab();
        }
    }

    private LinearLayout makeCard(String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(boxBg(COL_CARD, COL_BORDER, 10));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(COL_AMBER);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(8));
        card.addView(h);
        tabContent.addView(card);
        return card;
    }

    private Button makeActionButton(String text, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF0B0D10 : COL_TEXT);
        b.setBackground(boxBg(primary ? COL_AMBER : 0xFF1F2631, primary ? 0 : COL_BORDER, 8));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private void addSpinnerRow(LinearLayout parent, String label, String[] options, int initial, final IntConsumer onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parentGroup) {
                TextView v = (TextView) super.getView(position, convertView, parentGroup);
                v.setTextColor(COL_AMBER);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parentGroup) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parentGroup);
                v.setBackgroundColor(COL_CARD);
                v.setTextColor(COL_TEXT);
                v.setPadding(dp(12), dp(10), dp(12), dp(10));
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        int safeIdx = Math.max(0, Math.min(options.length - 1, initial));
        sp.setSelection(safeIdx);
        sp.setBackground(boxBg(0xFF1E242E, COL_BORDER, 6));
        sp.setPadding(dp(8), dp(4), dp(8), dp(4));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                onChange.accept(pos);
                saveSettings();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        row.addView(sp);
        parent.addView(row);
    }

    private void addSliderRow(LinearLayout parent, final String label, final int min, int max, int current,
                              final String suffix, final float scale, final IntConsumer onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));

        final TextView tv = new TextView(this);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setText(formatSlider(label, current, scale, suffix));
        box.addView(tv);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, current - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = min + progress;
                tv.setText(formatSlider(label, val, scale, suffix));
                onChange.accept(val);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveSettings();
            }
        });
        box.addView(sb);
        parent.addView(box);
    }

    private String formatSlider(String label, int val, float scale, String suffix) {
        if (scale == 1f) {
            return label + ": " + val + suffix;
        }
        return String.format(Locale.US, "%s: %.2f%s", label, val * scale, suffix);
    }

    // ---- TAB 1: GAME FOLDER & MODE ----

    private void buildGameTab() {
        LinearLayout folderCard = makeCard("CARPETA DE ARCHIVOS DEL JUEGO");

        gamePathView = new TextView(this);
        gamePathView.setTextColor(COL_TEXT);
        gamePathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        folderCard.addView(gamePathView);

        gameStatusView = new TextView(this);
        gameStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gameStatusView.setPadding(0, dp(8), 0, dp(4));
        folderCard.addView(gameStatusView);

        folderCard.addView(makeActionButton("SELECCIONAR CARPETA DEL JUEGO (CUALQUIER RUTA)", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickGameFolder();
            }
        }));

        folderCard.addView(makeActionButton("RESTAURAR CARPETA POR DEFECTO", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = new File(filesDir, "game_path.txt");
                if (f.exists()) {
                    f.delete();
                }
                refreshGameStatus();
                Toast.makeText(LauncherActivity.this, "Usando carpeta por defecto", Toast.LENGTH_SHORT).show();
            }
        }));

        LinearLayout savesCard = makeCard("PARTIDAS GUARDADAS (CHECKPOINTS ZOMBIES)");
        savesListBox = new LinearLayout(this);
        savesListBox.setOrientation(LinearLayout.VERTICAL);
        savesCard.addView(savesListBox);

        refreshGameStatus();
        refreshSavesList();
    }

    private void refreshSavesList() {
        if (savesListBox == null) {
            return;
        }
        savesListBox.removeAllViews();
        List<ZmSaveHelper.SlotInfo> saves = ZmSaveHelper.listSaves(filesDir);
        if (saves.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aún no tienes checkpoints guardados. Dentro de una partida de Zombies toca \"⚙ MENÚ\" arriba para guardar tu ronda.");
            empty.setTextColor(COL_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            savesListBox.addView(empty);
            return;
        }
        for (final ZmSaveHelper.SlotInfo s : saves) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(boxBg(0xFF10141A, COL_BORDER, 8));
            row.setPadding(dp(10), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t = new TextView(this);
            t.setText(s.friendlyMap() + " — Ronda " + s.round);
            t.setTextColor(COL_TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            col.addView(t);

            TextView sub = new TextView(this);
            sub.setText(s.summaryLine());
            sub.setTextColor(COL_MUTED);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            col.addView(sub);
            row.addView(col);

            Button loadBtn = new Button(this);
            loadBtn.setText("▶ Cargar");
            loadBtn.setAllCaps(false);
            loadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            loadBtn.setTypeface(Typeface.DEFAULT_BOLD);
            loadBtn.setTextColor(0xFF0B0D10);
            loadBtn.setBackground(boxBg(COL_AMBER, 0, 6));
            loadBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            loadBtn.setMinHeight(0);
            loadBtn.setMinimumHeight(dp(32));
            loadBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchGame(s.slotId, s.map);
                }
            });
            row.addView(loadBtn);

            Button delBtn = new Button(this);
            delBtn.setText("✕");
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            delBtn.setTypeface(Typeface.DEFAULT_BOLD);
            delBtn.setTextColor(COL_ERR);
            delBtn.setBackground(boxBg(0xFF1F2631, COL_BORDER, 6));
            delBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            delBtn.setMinHeight(0);
            delBtn.setMinimumHeight(dp(32));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = dp(6);
            delBtn.setLayoutParams(dlp);
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(LauncherActivity.this)
                            .setTitle("Eliminar guardado")
                            .setMessage("¿Eliminar " + s.friendlyMap() + " (Ronda " + s.round + ")?")
                            .setPositiveButton("Eliminar", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    ZmSaveHelper.deleteSlot(filesDir, s.slotId);
                                    refreshSavesList();
                                }
                            })
                            .setNegativeButton("Cancelar", null)
                            .show();
                }
            });
            row.addView(delBtn);
            savesListBox.addView(row);
        }
    }

    private File getActiveGameDir() {
        String custom = readSmallFile(new File(filesDir, "game_path.txt"));
        if (custom != null && custom.startsWith("/")) {
            return new File(custom);
        }
        return new File(filesDir, "game");
    }

    private boolean isGameDirValid(File dir) {
        return dir != null
                && new File(dir, "localization.txt").isFile()
                && new File(dir, "zone").isDirectory()
                && new File(dir, "main").isDirectory();
    }

    private void refreshGameStatus() {
        if (gamePathView == null || gameStatusView == null) {
            return;
        }
        File dir = getActiveGameDir();
        gamePathView.setText("Ruta activa:\n" + dir.getAbsolutePath());

        boolean hasLoc = new File(dir, "localization.txt").isFile();
        boolean hasZone = new File(dir, "zone").isDirectory();
        boolean hasMain = new File(dir, "main").isDirectory();
        boolean ok = hasLoc && hasZone && hasMain;

        String status = (ok ? "✓ Carpeta lista para jugar\n" : "✗ Faltan archivos requeridos en esta carpeta\n")
                + (hasLoc ? "  ✓ localization.txt" : "  ✗ localization.txt")
                + (hasZone ? "   ✓ zone/" : "   ✗ zone/")
                + (hasMain ? "   ✓ main/" : "   ✗ main/");
        gameStatusView.setText(status);
        gameStatusView.setTextColor(ok ? COL_OK : COL_ERR);
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void pickGameFolder() {
        if (!hasAllFilesAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permiso de almacenamiento")
                    .setMessage("Para leer la carpeta de Black Ops desde cualquier ubicación sin duplicar ~8 GB, activa \"Permitir acceso para administrar todos los archivos\" y luego vuelve aquí.")
                    .setPositiveButton("Activar permiso", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivityForResult(i, REQ_STORAGE_PERM);
                            } catch (Exception e) {
                                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_STORAGE_PERM);
                            }
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_GAME_DIR);
    }

    private String resolveTreeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId == null) {
                return null;
            }
            String[] split = docId.split(":", 2);
            String type = split[0];
            String rel = split.length > 1 ? split[1] : "";
            if ("primary".equalsIgnoreCase(type)) {
                String base = Environment.getExternalStorageDirectory().getAbsolutePath();
                return rel.isEmpty() ? base : (base + "/" + rel);
            }
            return "/storage/" + type + (rel.isEmpty() ? "" : ("/" + rel));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- TAB 2: VULKAN DRIVERS ----

    private void buildDriversTab() {
        LinearLayout card = makeCard("DRIVER VULKAN (ADRENOTOOLS / TURNIP)");
        TextView info = new TextView(this);
        info.setText("Elige el driver del sistema o importa un paquete .zip de Turnip (con meta.json y .so).");
        info.setTextColor(COL_MUTED);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(0, 0, 0, dp(8));
        card.addView(info);

        driverListBox = new LinearLayout(this);
        driverListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(driverListBox);

        card.addView(makeActionButton("IMPORTAR DRIVER TURNIP (.ZIP)", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/octet-stream" });
                startActivityForResult(i, REQ_IMPORT_DRIVER);
            }
        }));

        refreshDriverList();
    }

    private void refreshDriverList() {
        if (driverListBox == null) {
            return;
        }
        driverListBox.removeAllViews();
        String active = readSmallFile(new File(filesDir, "driver.txt"));
        if (active == null) {
            active = "";
        }

        addDriverRow("", "Driver del Sistema (Default)", "Usa el driver Vulkan nativo del equipo", active.isEmpty());

        File root = new File(filesDir, "driver");
        File[] dirs = root.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory()) {
                    continue;
                }
                String folder = d.getName();
                String title = folder;
                String desc = "driver/" + folder;
                File meta = new File(d, "meta.json");
                if (meta.isFile()) {
                    try {
                        JSONObject j = new JSONObject(readSmallFile(meta));
                        String n = j.optString("name", "");
                        String v = j.optString("driverVersion", "");
                        if (!n.isEmpty()) {
                            title = v.isEmpty() ? n : (n + " (" + v + ")");
                        }
                    } catch (Exception ignored) {
                    }
                }
                addDriverRow(folder, title, desc, folder.equals(active));
            }
        }
    }

    private void addDriverRow(final String folderName, String title, String subtitle, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(boxBg(selected ? 0xFF232018 : 0xFF10141A, selected ? COL_AMBER : COL_BORDER, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText((selected ? "●  " : "○  ") + title);
        t.setTextColor(selected ? COL_AMBER : COL_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(COL_MUTED);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        row.addView(s);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File driverTxt = new File(filesDir, "driver.txt");
                if (folderName.isEmpty()) {
                    if (driverTxt.exists()) {
                        driverTxt.delete();
                    }
                } else {
                    writeSmallFile(driverTxt, folderName + "\n");
                }
                refreshDriverList();
            }
        });
        driverListBox.addView(row);
    }

    private void importDriverZip(Uri uri) {
        String zipName = queryDisplayName(uri);
        if (zipName == null || zipName.isEmpty()) {
            zipName = "turnip_custom";
        }
        String folder = zipName.replaceAll("(?i)\\.zip$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        if (folder.isEmpty()) {
            folder = "turnip_custom";
        }
        File destDir = new File(new File(filesDir, "driver"), folder);
        destDir.mkdirs();

        int extracted = 0;
        boolean foundSo = false;
        try (InputStream is = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String baseName = new File(entry.getName()).getName();
                if (baseName.isEmpty() || baseName.contains("..")) {
                    continue;
                }
                if (baseName.endsWith(".so") || baseName.equalsIgnoreCase("meta.json")) {
                    File out = new File(destDir, baseName);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    extracted++;
                    if (baseName.endsWith(".so")) {
                        foundSo = true;
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error al importar .zip: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        if (!foundSo || extracted == 0) {
            Toast.makeText(this, "El .zip no contiene un driver Vulkan (.so) válido", Toast.LENGTH_LONG).show();
            return;
        }
        writeSmallFile(new File(filesDir, "driver.txt"), folder + "\n");
        refreshDriverList();
        Toast.makeText(this, "Driver importado y seleccionado: " + folder, Toast.LENGTH_SHORT).show();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- TAB 3: GRAPHICS & PERFORMANCE ----

    private void buildGraphicsTab() {
        LinearLayout card = makeCard("GRÁFICOS Y RENDIMIENTO");

        addSpinnerRow(card, "Renderer", new String[] { "Vulkan (DXVK)", "OpenGL ES 3" },
                renderer == 1 ? 0 : 1, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        renderer = (v == 0) ? 1 : 0;
                    }
                });

        addSpinnerRow(card, "Perfil gráfico", new String[] {
                "Móvil Optimizado (Recomendado)", "Alto / PC Original", "Gama Baja / Máximo FPS"
        }, gfxProfile, new IntConsumer() {
            @Override
            public void accept(int v) {
                gfxProfile = v;
            }
        });

        addSpinnerRow(card, "Resolución", new String[] {
                "Mantener actual", "1200x540 (Rendimiento)", "1600x720 (720p)",
                "1800x810 (75% Pro)", "2000x900 (900p)", "Nativa del panel"
        }, resIndex, new IntConsumer() {
            @Override
            public void accept(int v) {
                resIndex = v;
            }
        });

        addSpinnerRow(card, "Límite de FPS", new String[] { "60 FPS", "90 FPS", "120 FPS", "Sin límite" },
                fpsIndex, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        fpsIndex = v;
                    }
                });

        addSpinnerRow(card, "Sincronización (VSync)", new String[] { "Desactivado", "Activado" },
                vsync, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        vsync = v;
                    }
                });

        addSpinnerRow(card, "Sombras dinámicas", new String[] { "Activadas", "Desactivadas" },
                shadows == 1 ? 0 : 1, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        shadows = (v == 0) ? 1 : 0;
                    }
                });

        addSpinnerRow(card, "Shader Warming (al cargar mapa)", new String[] { "Activado", "Desactivado" },
                shaderWarming == 1 ? 0 : 1, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        shaderWarming = (v == 0) ? 1 : 0;
                    }
                });

        addSpinnerRow(card, "Nuevos Shaders Vulkan", new String[] {
                "Esperar (sin parpadeo)", "Efectos en 2do plano", "Todo en 2do plano (Sin tirones)"
        }, vkAsync, new IntConsumer() {
            @Override
            public void accept(int v) {
                vkAsync = v;
            }
        });

        addSpinnerRow(card, "Contador de FPS", new String[] { "Ninguno", "Simple", "Avanzado", "Completo" },
                fpsCounter, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        fpsCounter = v;
                    }
                });

        addSpinnerRow(card, "Carga Directa / Bypass (Root)", new String[] {
                "Desactivada (Default)", "Activada (Experimental)"
        }, bypassCharging, new IntConsumer() {
            @Override
            public void accept(int v) {
                bypassCharging = v;
            }
        });
    }

    // ---- TAB 4: CONTROLS ----

    private void buildControlsTab() {
        LinearLayout touchCard = makeCard("CONTROLES EN PANTALLA (HD + MENÚ)");

        touchCard.addView(makeActionButton("EDITAR CONTROLES EN PANTALLA COMPLETA (1:1)", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                startActivity(new Intent(LauncherActivity.this, TouchEditorActivity.class));
            }
        }));

        addSpinnerRow(touchCard, "Controles táctiles", new String[] { "Activados", "Desactivados" },
                touchEnabled == 1 ? 0 : 1, new IntConsumer() {
                    @Override
                    public void accept(int v) {
                        touchEnabled = (v == 0) ? 1 : 0;
                    }
                });

        addSpinnerRow(touchCard, "Estilo visual", new String[] {
                "CODM / Shooter HD", "Xbox Digital", "PlayStation Digital"
        }, touchStyle, new IntConsumer() {
            @Override
            public void accept(int v) {
                touchStyle = v;
            }
        });

        addSpinnerRow(touchCard, "Perfil precargado", new String[] {
                "CODM (2 Dedos)", "Claw 4 Dedos", "Gamepad / Xbox"
        }, touchPreset, new IntConsumer() {
            @Override
            public void accept(int v) {
                touchPreset = v;
            }
        });

        addSliderRow(touchCard, "Sensibilidad táctil", 4, 30, touchSensPct, "", 0.1f, new IntConsumer() {
            @Override
            public void accept(int v) {
                touchSensPct = v;
            }
        });

        addSliderRow(touchCard, "Opacidad de botones", 10, 100, touchOpacityPct, "%", 1f, new IntConsumer() {
            @Override
            public void accept(int v) {
                touchOpacityPct = v;
            }
        });

        LinearLayout padCard = makeCard("MANDO (BLUETOOTH / USB)");
        addSpinnerRow(padCard, "Vibración del mando", new String[] {
                "Desactivada (0% cortes BT)", "Segura BT (1 motor suave)", "Completa (USB / Cable)"
        }, padRumble, new IntConsumer() {
            @Override
            public void accept(int v) {
                padRumble = v;
            }
        });
        addSliderRow(padCard, "Zona muerta de sticks", 5, 25, padDeadzonePct, "", 0.01f, new IntConsumer() {
            @Override
            public void accept(int v) {
                padDeadzonePct = v;
            }
        });
    }

    // ---- LAUNCH ----

    private String getSelectedResolution() {
        if (resIndex == 1) return "1200x540";
        if (resIndex == 2) return "1600x720";
        if (resIndex == 3) return "1800x810";
        if (resIndex == 4) return "2000x900";
        if (resIndex == 5) {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int w = Math.max(dm.widthPixels, dm.heightPixels);
            int h = Math.min(dm.widthPixels, dm.heightPixels);
            if (w >= 640 && h >= 360) {
                return w + "x" + h;
            }
        }
        return null;
    }

    private void launchGame() {
        launchGame(null, null);
    }

    private void launchGame(String autoloadSlot, String autoloadMap) {
        File gameDir = getActiveGameDir();
        if (!isGameDirValid(gameDir)) {
            selectTab(0);
            new AlertDialog.Builder(this)
                    .setTitle("Faltan archivos del juego")
                    .setMessage("La carpeta seleccionada no contiene localization.txt, zone/ y main/.\n\nSelecciona la carpeta correcta de Call of Duty: Black Ops en la pestaña JUEGO.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        saveSettings();

        int maxFps = (fpsIndex == 0) ? 60 : (fpsIndex == 1 ? 90 : (fpsIndex == 2 ? 120 : 0));
        int aimDrag = prefs.getInt("touchAimDrag", 1);
        int autoSprint = prefs.getInt("touchAutoSprint", 1);
        int sprintThreshPct = prefs.getInt("touchSprintThreshPct", 85);
        int aimOnFire = prefs.getInt("touchAimOnFire", 0);
        int autoFire = prefs.getInt("touchAutoFire", 1);

        StringBuilder args = new StringBuilder();
        args.append("+set bo1_zombies 1");
        args.append(" +set bo1_engine_fps 1");
        args.append(" +set bo1_renderer ").append(renderer);
        args.append(" +set r_vsync ").append(vsync);
        args.append(" +set com_maxfps ").append(maxFps);
        args.append(" +set sm_enable ").append(shadows);
        args.append(" +set r_shaderWarming ").append(shaderWarming);
        args.append(" +set bo1_vk_async ").append(vkAsync);
        args.append(" +set bo1_fpscounter ").append(fpsCounter);
        args.append(" +set bo1_touch ").append(touchEnabled);
        args.append(" +set bo1_touch_style ").append(touchStyle);
        args.append(" +set bo1_touch_preset ").append(touchPreset);
        args.append(" +set bo1_touch_aim_drag ").append(aimDrag);
        args.append(" +set bo1_touch_autosprint ").append(autoSprint);
        args.append(String.format(Locale.US, " +set bo1_touch_sprint_thresh %.2f", sprintThreshPct * 0.01f));
        args.append(" +set bo1_touch_aim_fire ").append(aimOnFire);
        args.append(" +set bo1_touch_autofire ").append(autoFire);
        args.append(String.format(Locale.US, " +set bo1_touch_sensitivity %.2f", touchSensPct * 0.1f));
        args.append(String.format(Locale.US, " +set bo1_touch_opacity %.2f", touchOpacityPct * 0.01f));
        args.append(String.format(Locale.US, " +set gpad_stick_deadzone_min %.2f", padDeadzonePct * 0.01f));
        args.append(" +set gpad_rumble ").append(padRumble == 0 ? 0 : 1);

        // Apply universal mobile graphics profile to config.cfg (avoids inflating sys_cmdline 1024-byte limit)
        // and pass the highest-impact flags for first-time installs before config.cfg exists.
        applyGfxProfileToConfigs(gfxProfile);
        if (gfxProfile == 1) {
            args.append(" +set r_depthPrepass 0 +set r_multiGpu 0");
        } else {
            args.append(" +set r_depthPrepass 0 +set r_multiGpu 0 +set r_zfeather 0 +set r_distortion 0");
        }

        String mode = getSelectedResolution();
        if (mode != null) {
            args.append(" +set r_mode ").append(mode);
        }
        if (autoloadSlot != null && !autoloadSlot.isEmpty() && autoloadMap != null && !autoloadMap.isEmpty()) {
            args.append(" +set systemlink 0 +set bo1_expected_players 0 +set bo1_zm_autoload ")
                    .append(autoloadSlot)
                    .append(" +map ")
                    .append(autoloadMap);
        }

        writeSmallFile(new File(filesDir, "args.txt"), args.toString());

        Intent intent = new Intent(this, Bo1Activity.class);
        startActivity(intent);
        finish();
    }

    private void applyGfxProfileToConfigs(int profile) {
        String[][] pairs;
        if (profile == 1) {
            // High / PC Original (still keeps r_depthPrepass 0 and r_multiGpu 0 for mobile tiled GPUs)
            pairs = new String[][] {
                    { "r_depthPrepass", "0" },
                    { "r_aaAlpha", "dither (fast)" },
                    { "r_aaSamples", "1" },
                    { "r_texFilterAnisoMin", "4" },
                    { "r_texFilterAnisoMax", "8" },
                    { "r_zfeather", "1" },
                    { "r_distortion", "1" },
                    { "r_dof_enable", "1" },
                    { "r_multiGpu", "0" },
                    { "sm_maxLights", "4" },
                    { "sm_qualitySpotShadow", "1" },
                    { "r_gfxopt_dynamic_foliage", "1" },
                    { "r_picmip", "0" },
                    { "r_picmip_bump", "0" },
                    { "r_picmip_spec", "0" },
            };
        } else if (profile == 2) {
            // Low / Max FPS (best for entry-level Mali / Helio GPUs or chasing 120 FPS)
            pairs = new String[][] {
                    { "r_depthPrepass", "0" },
                    { "r_aaAlpha", "off" },
                    { "r_aaSamples", "1" },
                    { "r_texFilterAnisoMin", "1" },
                    { "r_texFilterAnisoMax", "2" },
                    { "r_zfeather", "0" },
                    { "r_distortion", "0" },
                    { "r_dof_enable", "0" },
                    { "r_multiGpu", "0" },
                    { "sm_maxLights", "1" },
                    { "sm_qualitySpotShadow", "0" },
                    { "r_gfxopt_dynamic_foliage", "0" },
                    { "r_picmip", "1" },
                    { "r_picmip_bump", "1" },
                    { "r_picmip_spec", "1" },
            };
        } else {
            // 0 = Mobile Optimized (Recommended): full crisp textures & lighting, lightweight 512p shadows
            pairs = new String[][] {
                    { "r_depthPrepass", "0" },
                    { "r_aaAlpha", "off" },
                    { "r_aaSamples", "1" },
                    { "r_texFilterAnisoMin", "1" },
                    { "r_texFilterAnisoMax", "4" },
                    { "r_zfeather", "0" },
                    { "r_distortion", "0" },
                    { "r_dof_enable", "0" },
                    { "r_multiGpu", "0" },
                    { "sm_maxLights", "1" },
                    { "sm_qualitySpotShadow", "0" },
                    { "r_gfxopt_dynamic_foliage", "0" },
                    { "r_picmip", "0" },
                    { "r_picmip_bump", "0" },
                    { "r_picmip_spec", "0" },
            };
        }

        File playersDir = new File(new File(filesDir, "home"), "players");
        updateCfgKeys(new File(playersDir, "config.cfg"), pairs);
        updateCfgKeys(new File(playersDir, "config_mp.cfg"), pairs);
    }

    private void updateCfgKeys(File cfgFile, String[][] pairs) {
        if (!cfgFile.isFile()) {
            return;
        }
        String content = readSmallFile(cfgFile);
        if (content == null || content.isEmpty()) {
            return;
        }
        String[] lines = content.split("\r?\n");
        boolean[] seen = new boolean[pairs.length];
        StringBuilder out = new StringBuilder(content.length() + 256);
        for (String line : lines) {
            String trimmed = line.trim();
            boolean replaced = false;
            for (int i = 0; i < pairs.length; i++) {
                String prefix = "seta " + pairs[i][0] + " ";
                if (trimmed.startsWith(prefix)) {
                    out.append(prefix).append('"').append(pairs[i][1]).append('"').append('\n');
                    seen[i] = true;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                out.append(line).append('\n');
            }
        }
        for (int i = 0; i < pairs.length; i++) {
            if (!seen[i]) {
                out.append("seta ").append(pairs[i][0]).append(" \"").append(pairs[i][1]).append("\"\n");
            }
        }
        writeSmallFile(cfgFile, out.toString());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STORAGE_PERM) {
            if (hasAllFilesAccess()) {
                pickGameFolder();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_GAME_DIR) {
            String path = resolveTreeUriToPath(uri);
            if (path == null) {
                Toast.makeText(this, "No se pudo resolver la ruta de esa carpeta", Toast.LENGTH_LONG).show();
                return;
            }
            File dir = new File(path);
            if (!isGameDirValid(dir)) {
                new AlertDialog.Builder(this)
                        .setTitle("Carpeta incompleta")
                        .setMessage("En:\n" + path + "\n\nDeben existir:\n• localization.txt\n• carpeta zone/\n• carpeta main/")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            writeSmallFile(new File(filesDir, "game_path.txt"), path + "\n");
            refreshGameStatus();
            Toast.makeText(this, "¡Carpeta de Black Ops I configurada!", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_IMPORT_DRIVER) {
            importDriverZip(uri);
        }
    }

    private String readSmallFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeSmallFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
