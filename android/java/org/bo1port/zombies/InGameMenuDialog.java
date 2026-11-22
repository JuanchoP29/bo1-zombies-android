package org.bo1port.zombies;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Minimalist floating in-game quick menu (accessible from the discreet top pill near the camera cutout).
 * Allows live adjustment of touch controls, tactile response, controller vibration, Zombies checkpoints
 * (save/load/delete), experimental root Bypass Charging, and instant return to the main menu.
 */
public final class InGameMenuDialog {
    private static final String PREFS = "bo1_launcher_prefs";

    private static final int COL_BG = 0xF210141B;
    private static final int COL_CARD = 0xFF171D26;
    private static final int COL_BORDER = 0xFF2B3544;
    private static final int COL_AMBER = 0xFFF5921E;
    private static final int COL_TEXT = 0xFFEAECEF;
    private static final int COL_MUTED = 0xFF98A2B3;
    private static final int COL_ERR = 0xFFE74C3C;

    private final Bo1Activity activity;
    private final SharedPreferences prefs;
    private final File filesDir;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private int currentTab = 0;
    private final Button[] tabBtns = new Button[3];
    private LinearLayout contentBox;
    private LinearLayout savesListBox;
    private TextView batteryStatusTv;

    public InGameMenuDialog(Bo1Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Bo1Activity.MODE_PRIVATE);
        File ext = activity.getExternalFilesDir(null);
        this.filesDir = ext != null ? ext : activity.getFilesDir();
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void show() {
        if (isShowing()) {
            return;
        }
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(boxBg(COL_BG, COL_AMBER, 12));
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(activity);
        title.setText("MENÚ RÁPIDO · BLACK OPS I");
        title.setTextColor(COL_AMBER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        header.addView(makeSmallBtn("REANUDAR", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        }));
        root.addView(header);

        // Tabs
        LinearLayout tabBar = new LinearLayout(activity);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbLp.bottomMargin = dp(8);
        tabBar.setLayoutParams(tbLp);

        String[] names = { "CONTROLES", "GUARDADOS", "SISTEMA" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(activity);
            b.setText(names[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(6), dp(6), dp(6), dp(6));
            b.setMinHeight(0);
            b.setMinimumHeight(dp(34));
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
            tabBtns[i] = b;
            tabBar.addView(b);
        }
        root.addView(tabBar);

        // Scrollable tab content
        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(235)));
        contentBox = new LinearLayout(activity);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contentBox);
        root.addView(scroll);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(dp(560), (int) (screenW * 0.84f)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        selectTab(0);
        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void selectTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < tabBtns.length; i++) {
            boolean active = (i == idx);
            tabBtns[i].setBackground(boxBg(active ? COL_AMBER : COL_CARD, active ? 0 : COL_BORDER, 8));
            tabBtns[i].setTextColor(active ? 0xFF0B0D10 : COL_TEXT);
        }
        contentBox.removeAllViews();
        if (idx == 0) {
            buildControlsTab();
        } else if (idx == 1) {
            buildSavesTab();
        } else {
            buildSystemTab();
        }
    }

    // ---- TAB 0: LIVE CONTROLS & VIBRATION ----

    private void buildControlsTab() {
        int touchOn = prefs.getInt("touchEnabled", 1);
        int style = prefs.getInt("touchStyle", 0);
        int opacityPct = prefs.getInt("touchOpacityPct", 45);
        int sensPct = prefs.getInt("touchSensPct", 12);
        boolean aimDrag = prefs.getInt("touchAimDrag", 1) != 0;
        boolean autoSprint = prefs.getInt("touchAutoSprint", 1) != 0;
        int sprintPct = prefs.getInt("touchSprintThreshPct", 85);
        boolean aimFire = prefs.getInt("touchAimOnFire", 0) != 0;
        boolean autoFire = prefs.getInt("touchAutoFire", 1) != 0;
        int rumbleMode = prefs.getInt("padRumble", 1);

        addSpinnerRow("Controles táctiles", new String[] { "Activados", "Desactivados" },
                touchOn == 1 ? 0 : 1, new IntAction() {
                    @Override
                    public void run(int pos) {
                        int val = (pos == 0) ? 1 : 0;
                        prefs.edit().putInt("touchEnabled", val).apply();
                        sendCmd("set bo1_touch " + val);
                    }
                });

        addSpinnerRow("Estilo visual", new String[] { "CODM / Shooter HD", "Xbox Digital", "PlayStation Digital" },
                style, new IntAction() {
                    @Override
                    public void run(int pos) {
                        prefs.edit().putInt("touchStyle", pos).apply();
                        sendCmd("set bo1_touch_style " + pos);
                    }
                });

        addSpinnerRow("Vibración del mando", new String[] {
                "Desactivada (0% BT)", "Segura BT (1 motor)", "Completa (2 motores)"
        }, rumbleMode, new IntAction() {
            @Override
            public void run(int pos) {
                prefs.edit().putInt("padRumble", pos).apply();
                activity.setPadRumbleMode(pos);
                sendCmd("set gpad_rumble " + (pos == 0 ? 0 : 1));
            }
        });

        addSliderRow("Sensibilidad táctil", 4, 30, sensPct, "", 0.1f, new IntAction() {
            @Override
            public void run(int val) {
                prefs.edit().putInt("touchSensPct", val).apply();
                sendCmd(String.format(Locale.US, "set bo1_touch_sensitivity %.2f", val * 0.1f));
            }
        });

        addSliderRow("Opacidad de botones", 10, 100, opacityPct, "%", 1f, new IntAction() {
            @Override
            public void run(int val) {
                prefs.edit().putInt("touchOpacityPct", val).apply();
                sendCmd(String.format(Locale.US, "set bo1_touch_opacity %.2f", val * 0.01f));
            }
        });

        addCheckRow("Arrastrar cámara desde Apuntar", aimDrag, new BoolAction() {
            @Override
            public void run(boolean on) {
                prefs.edit().putInt("touchAimDrag", on ? 1 : 0).apply();
                sendCmd("set bo1_touch_aim_drag " + (on ? 1 : 0));
            }
        });

        addCheckRow("Correr omnidireccional por joystick", autoSprint, new BoolAction() {
            @Override
            public void run(boolean on) {
                prefs.edit().putInt("touchAutoSprint", on ? 1 : 0).apply();
                sendCmd("set bo1_touch_autosprint " + (on ? 1 : 0));
            }
        });

        addSliderRow("Umbral caminar → correr", 50, 98, sprintPct, "%", 1f, new IntAction() {
            @Override
            public void run(int val) {
                prefs.edit().putInt("touchSprintThreshPct", val).apply();
                sendCmd(String.format(Locale.US, "set bo1_touch_sprint_thresh %.2f", val * 0.01f));
            }
        });

        addCheckRow("Apuntar al disparar (botón principal)", aimFire, new BoolAction() {
            @Override
            public void run(boolean on) {
                prefs.edit().putInt("touchAimOnFire", on ? 1 : 0).apply();
                sendCmd("set bo1_touch_aim_fire " + (on ? 1 : 0));
            }
        });

        addCheckRow("Disparo continuo (armas semiauto)", autoFire, new BoolAction() {
            @Override
            public void run(boolean on) {
                prefs.edit().putInt("touchAutoFire", on ? 1 : 0).apply();
                sendCmd("set bo1_touch_autofire " + (on ? 1 : 0));
            }
        });
    }

    // ---- TAB 1: ZOMBIES CHECKPOINTS ----

    private void buildSavesTab() {
        TextView info = new TextView(activity);
        info.setText("Guarda un Checkpoint de Zombies (Mapa, Ronda exacta, Puntos, Armas + Pack-a-Punch, Munición y Perks). Al cargar arrancas limpio al inicio de tu ronda.");
        info.setTextColor(COL_MUTED);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        info.setPadding(0, 0, 0, dp(8));
        contentBox.addView(info);

        Button saveNowBtn = makeFullBtn("+ GUARDAR PARTIDA ACTUAL (ZOMBIES)", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String slotId = "zm_" + (System.currentTimeMillis() / 1000L);
                sendCmd("zm_save " + slotId);
                Toast.makeText(activity, "Guardando checkpoint de Zombies...", Toast.LENGTH_SHORT).show();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshSavesList();
                    }
                }, 450);
            }
        });
        contentBox.addView(saveNowBtn);

        savesListBox = new LinearLayout(activity);
        savesListBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        savesListBox.setLayoutParams(lp);
        contentBox.addView(savesListBox);

        refreshSavesList();
    }

    private void refreshSavesList() {
        if (savesListBox == null) {
            return;
        }
        savesListBox.removeAllViews();
        List<ZmSaveHelper.SlotInfo> saves = ZmSaveHelper.listSaves(filesDir);
        if (saves.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No hay partidas guardadas todavía.");
            empty.setTextColor(COL_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(dp(6), dp(8), dp(6), dp(8));
            savesListBox.addView(empty);
            return;
        }

        for (final ZmSaveHelper.SlotInfo s : saves) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(boxBg(COL_CARD, COL_BORDER, 8));
            row.setPadding(dp(10), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t = new TextView(activity);
            t.setText(s.friendlyMap() + " — Ronda " + s.round);
            t.setTextColor(COL_TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            col.addView(t);

            TextView sub = new TextView(activity);
            sub.setText(s.summaryLine());
            sub.setTextColor(COL_MUTED);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            col.addView(sub);
            row.addView(col);

            row.addView(makeSmallBtn("Cargar", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendCmd("zm_load " + s.slotId);
                    Toast.makeText(activity, "Cargando " + s.friendlyMap() + " (Ronda " + s.round + ")...", Toast.LENGTH_SHORT).show();
                    dismiss();
                }
            }));

            Button del = makeSmallBtn("✕", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(activity)
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
            del.setTextColor(COL_ERR);
            row.addView(del);
            savesListBox.addView(row);
        }
    }

    // ---- TAB 2: SYSTEM & EXPERIMENTAL ROOT BYPASS ----

    private void buildSystemTab() {
        boolean bypassOn = prefs.getInt("bypassCharging", 0) != 0;

        LinearLayout bypassCard = new LinearLayout(activity);
        bypassCard.setOrientation(LinearLayout.VERTICAL);
        bypassCard.setBackground(boxBg(COL_CARD, COL_BORDER, 8));
        bypassCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(10);
        bypassCard.setLayoutParams(blp);

        final CheckBox cbBypass = new CheckBox(activity);
        cbBypass.setText("Carga Directa / Bypass (Experimental · Root)");
        cbBypass.setTextColor(COL_TEXT);
        cbBypass.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cbBypass.setChecked(bypassOn && BypassChargingHelper.isActive());
        cbBypass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, final boolean isChecked) {
                prefs.edit().putInt("bypassCharging", isChecked ? 1 : 0).apply();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (isChecked) {
                            BypassChargingHelper.enable();
                        } else {
                            BypassChargingHelper.restore();
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateBatteryTelemetry();
                            }
                        });
                    }
                }).start();
            }
        });
        bypassCard.addView(cbBypass);

        batteryStatusTv = new TextView(activity);
        batteryStatusTv.setTextColor(COL_MUTED);
        batteryStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        batteryStatusTv.setPadding(dp(6), dp(4), dp(6), dp(6));
        bypassCard.addView(batteryStatusTv);
        updateBatteryTelemetry();

        bypassCard.addView(makeSmallBtn("Actualizar lectura de mA / °C", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateBatteryTelemetry();
            }
        }));
        contentBox.addView(bypassCard);

        Button exitMenuBtn = makeFullBtn("SALIR AL MENÚ PRINCIPAL (DISCONNECT)", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCmd("disconnect");
                dismiss();
            }
        });
        exitMenuBtn.setTextColor(COL_AMBER);
        contentBox.addView(exitMenuBtn);
    }

    private void updateBatteryTelemetry() {
        if (batteryStatusTv != null) {
            batteryStatusTv.setText(BypassChargingHelper.getBatteryTelemetry(activity));
        }
    }

    // ---- HELPERS ----

    private interface IntAction { void run(int value); }
    private interface BoolAction { void run(boolean value); }

    private void sendCmd(String cmd) {
        try {
            Bo1Activity.ssCommand(cmd);
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics()));
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

    private Button makeSmallBtn(String text, boolean primary, View.OnClickListener click) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF0B0D10 : COL_TEXT);
        b.setBackground(boxBg(primary ? COL_AMBER : 0xFF232C38, primary ? 0 : COL_BORDER, 6));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(30));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private Button makeFullBtn(String text, boolean primary, View.OnClickListener click) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF0B0D10 : COL_TEXT);
        b.setBackground(boxBg(primary ? COL_AMBER : 0xFF232C38, primary ? 0 : COL_BORDER, 8));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private void addSpinnerRow(String label, String[] options, int initial, final IntAction onChange) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        Spinner sp = new Spinner(activity);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, cv, parent);
                v.setTextColor(COL_AMBER);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                return v;
            }
            @Override
            public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(pos, cv, parent);
                v.setBackgroundColor(COL_CARD);
                v.setTextColor(COL_TEXT);
                v.setPadding(dp(10), dp(8), dp(10), dp(8));
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(options.length - 1, initial)));
        sp.setBackground(boxBg(0xFF1F2631, COL_BORDER, 6));
        sp.setPadding(dp(8), dp(2), dp(8), dp(2));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                onChange.run(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        row.addView(sp);
        contentBox.addView(row);
    }

    private void addSliderRow(final String label, final int min, int max, int current,
                              final String suffix, final float scale, final IntAction onChange) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(3), 0, dp(3));

        final TextView tv = new TextView(activity);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setText(formatSlider(label, current, scale, suffix));
        box.addView(tv);

        SeekBar sb = new SeekBar(activity);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, current - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int val = min + p;
                tv.setText(formatSlider(label, val, scale, suffix));
                if (fromUser) {
                    onChange.run(val);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb);
        contentBox.addView(box);
    }

    private String formatSlider(String label, int val, float scale, String suffix) {
        if (scale == 1f) {
            return label + ": " + val + suffix;
        }
        return String.format(Locale.US, "%s: %.2f%s", label, val * scale, suffix);
    }

    private void addCheckRow(String label, boolean initial, final BoolAction onChange) {
        CheckBox cb = new CheckBox(activity);
        cb.setText(label);
        cb.setTextColor(COL_TEXT);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cb.setChecked(initial);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                onChange.run(isChecked);
            }
        });
        contentBox.addView(cb);
    }
}
