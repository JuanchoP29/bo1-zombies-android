package org.bo1port.zombies;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.CombinedVibration;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import java.io.File;
import java.io.RandomAccessFile;

import org.libsdl.app.SDLActivity;

/**
 * The app's only activity: SDL's, with the two native libraries it loads itself. libmain.so is the loader stub
 * (src/native/android/bo1_boot.c); the engine, libbo1.so, is not listed because the stub loads it at an address
 * below 4 GB, which System.loadLibrary cannot do.
 *
 * restartApp and shareLog are called by the engine (src/native/native_extras.cpp, through JNI).
 *
 * The second-screen controller (SecondScreen, a dual-screen device's other display): the ss* methods are the
 * engine's, registered by libbo1.so (src/native/native_second_screen.cpp); secondScreenState and secondScreenBinds
 * are called by it.
 */
public class Bo1Activity extends SDLActivity {
    static native void ssLook(float dx, float dy);
    static native void ssStick(float x, float y);
    static native void ssKey(int key, boolean down);
    static native void ssCommand(String command);
    static native void ssRequestBinds(String keys);
    static native void ssVisible(boolean visible);
    static native void ssLog(String text);
    static native int touchHudRead(float[] buf);

    private SecondScreen secondScreen;
    private TouchHudOverlay touchOverlay;
    private InGameMenuDialog quickMenu;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    /** The display's mode with the highest refresh rate at the current resolution (a 120 Hz panel: its 120 Hz mode). */
    private Display.Mode fastestMode() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        return best;
    }

    /**
     * Asks for that mode: without a request Android may run a game's window at 60 Hz on a faster panel.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredDisplayModeId = fastestMode().getModeId();
            getWindow().setAttributes(params);
        } catch (Throwable ignored) {
            // the system's choice stays
        }
        try {
            secondScreen = new SecondScreen(this);
        } catch (Throwable t) {
            secondScreen = null;   // no second-screen controller; the game is not affected
        }
        try {
            padRumbleMode = getSharedPreferences("bo1_launcher_prefs", MODE_PRIVATE).getInt("padRumble", 1);
        } catch (Throwable ignored) {
            padRumbleMode = 1;
        }
        try {
            if (mLayout != null) {
                touchOverlay = new TouchHudOverlay(this);
                RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                mLayout.addView(touchOverlay, lp);
            }
        } catch (Throwable t) {
            touchOverlay = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (secondScreen != null) {
            secondScreen.start();
        }
        if (touchOverlay != null) {
            touchOverlay.startOverlay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (getSharedPreferences("bo1_launcher_prefs", MODE_PRIVATE).getInt("bypassCharging", 0) != 0) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        BypassChargingHelper.enable();
                    }
                }).start();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        if (BypassChargingHelper.isActive()) {
            BypassChargingHelper.restore();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (BypassChargingHelper.isActive()) {
            BypassChargingHelper.restore();
        }
        if (padRumbleMode != 0 && lastRumbleAtMs > 0L) {
            setControllerRumble("0 0");
        }
        if (quickMenu != null) {
            quickMenu.dismiss();
        }
        if (touchOverlay != null) {
            touchOverlay.stopOverlay();
        }
        if (secondScreen != null) {
            secondScreen.stop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (BypassChargingHelper.isActive()) {
            BypassChargingHelper.restore();
        }
        super.onDestroy();
    }

    public void setPadRumbleMode(int mode) {
        padRumbleMode = Math.max(0, Math.min(2, mode));
    }

    public void openQuickMenu(final boolean pauseIfIngame) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pauseIfIngame) {
                    try {
                        ssKey(27, true);
                        ssKey(27, false);
                    } catch (Throwable ignored) {
                    }
                }
                if (quickMenu == null) {
                    quickMenu = new InGameMenuDialog(Bo1Activity.this);
                }
                quickMenu.show();
            }
        });
    }

    private boolean rumbleLogged;
    private int padRumbleMode = 1; // 0=Off, 1=Safe BT (single-motor 160ms/200ms, no cancel spam), 2=Full USB
    private long lastRumbleAtMs = 0L;

    /**
     * Native Android gamepad rumble.
     * Mode 0: completely disabled (0 Bluetooth HID output packets ever).
     * Mode 1 (Safe BT): single-motor 160 ms finite pulse, 200 ms cooldown, capped amplitude (180),
     *   and NO cancel() HID spam so Xiaomi MiuiBluetooth / DualShock 4 never hits HID_HostWriteDev status:8.
     * Mode 2 (Full USB): dual-motor rumble with 120 ms cooldown.
     */
    public void setControllerRumble(String spec) {
        try {
            if (padRumbleMode == 0 || spec == null) {
                return;
            }
            String[] parts = spec.trim().split("\\s+");
            if (parts.length < 2) {
                return;
            }
            int low = Math.max(0, Math.min(255, Integer.parseInt(parts[0])));
            int high = Math.max(0, Math.min(255, Integer.parseInt(parts[1])));

            long now = SystemClock.uptimeMillis();
            boolean stopping = (low == 0 && high == 0);
            if (stopping && padRumbleMode == 1) {
                // In Safe BT mode, short 160 ms pulses stop automatically; skip redundant HID cancel packets.
                return;
            }
            long cooldown = (padRumbleMode == 1) ? 200L : 120L;
            if (!stopping && (now - lastRumbleAtMs) < cooldown) {
                return;
            }
            lastRumbleAtMs = now;

            int[] devIds = InputDevice.getDeviceIds();
            // Pass 0: standard gamepad/joystick InputDevice; Pass 1: any external non-virtual device with a vibrator
            for (int pass = 0; pass < 2; pass++) {
                for (int devId : devIds) {
                    InputDevice dev = InputDevice.getDevice(devId);
                    if (dev == null || dev.isVirtual()) {
                        continue;
                    }
                    int src = dev.getSources();
                    boolean isPad = ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                            || ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
                    if (pass == 0 ? !isPad : (isPad || dev.getVendorId() == 0)) {
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        VibratorManager vm = dev.getVibratorManager();
                        if (vm != null) {
                            int[] vibIds = vm.getVibratorIds();
                            if (vibIds != null && vibIds.length > 0) {
                                if (!rumbleLogged && !stopping) {
                                    rumbleLogged = true;
                                    Log.i("BO1Rumble", "VibratorManager on '" + dev.getName()
                                            + "' mode=" + padRumbleMode + " motors=" + vibIds.length);
                                }
                                if (stopping) {
                                    vm.cancel();
                                } else if (padRumbleMode == 2 && vibIds.length >= 2) {
                                    CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
                                    if (low > 0) {
                                        combo.addVibrator(vibIds[0], VibrationEffect.createOneShot(240, low));
                                    }
                                    if (high > 0) {
                                        combo.addVibrator(vibIds[1], VibrationEffect.createOneShot(240, high));
                                    }
                                    vm.vibrate(combo.combine());
                                } else {
                                    // Safe BT: single motor only (1 HID write instead of 2) + gentle cap (180) + 160 ms pulse
                                    int amp = Math.max(1, Math.min(padRumbleMode == 1 ? 180 : 255, Math.max(low, high)));
                                    CombinedVibration single = CombinedVibration.startParallel()
                                            .addVibrator(vibIds[0], VibrationEffect.createOneShot(160, amp))
                                            .combine();
                                    vm.vibrate(single);
                                }
                                return;
                            }
                        }
                    }
                    @SuppressWarnings("deprecation")
                    Vibrator vib = dev.getVibrator();
                    if (vib != null && vib.hasVibrator()) {
                        if (!rumbleLogged && !stopping) {
                            rumbleLogged = true;
                            Log.i("BO1Rumble", "Legacy Vibrator on '" + dev.getName() + "' mode=" + padRumbleMode);
                        }
                        int amp = Math.max(0, Math.min(padRumbleMode == 1 ? 180 : 255, Math.max(low, high)));
                        if (amp <= 0) {
                            vib.cancel();
                        } else {
                            vib.vibrate(VibrationEffect.createOneShot(160, amp));
                        }
                        return;
                    }
                }
            }
            if (!rumbleLogged && (low > 0 || high > 0)) {
                rumbleLogged = true;
                Log.w("BO1Rumble", "No controller vibrator found among " + devIds.length + " input devices");
            }
        } catch (Throwable t) {
            if (!rumbleLogged) {
                rumbleLogged = true;
                Log.w("BO1Rumble", "Controller rumble error: " + t);
            }
        }
    }

    /** From the engine, on its thread: "<bo1_second_screen> <in a game> <Zombies>" (SecondScreen.onState). */
    public void secondScreenState(String state) {
        if (touchOverlay != null) {
            touchOverlay.setNativesReady();
        }
        if (secondScreen != null) {
            secondScreen.onState(state);
        }
    }

    /** From the engine, on its thread: the binds asked for with ssRequestBinds, "KEY=binding" lines. */
    public void secondScreenBinds(String binds) {
        if (secondScreen != null) {
            secondScreen.onBinds(binds);
        }
    }

    /** For the loader stub: the display's refresh rates (the engine's SCREEN REFRESH RATE list). */
    @Override
    protected String[] getArguments() {
        return new String[] { "--rates=" + supportedRates() };
    }

    /** The display's refresh rates at the current resolution, ascending: "60,90,120". */
    private String supportedRates() {
        java.util.TreeSet<Integer> rates = new java.util.TreeSet<Integer>();
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                    rates.add(Math.round(mode.getRefreshRate()));
                }
            }
        } catch (Throwable ignored) {
            // 60 below
        }
        if (rates.isEmpty()) {
            rates.add(60);
        }
        StringBuilder text = new StringBuilder();
        for (int rate : rates) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(rate);
        }
        return text.toString();
    }

    /**
     * The engine's refresh rate setting (r_displayRefresh): asks for the display mode with that rate at the
     * current resolution, and tells the compositor the window's frame rate.
     */
    public void setRefreshRate(final String hz) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    int wanted = Integer.parseInt(hz);
                    Display display = getWindowManager().getDefaultDisplay();
                    Display.Mode current = display.getMode();
                    Display.Mode best = null;
                    for (Display.Mode mode : display.getSupportedModes()) {
                        if (mode.getPhysicalWidth() != current.getPhysicalWidth() || mode.getPhysicalHeight() != current.getPhysicalHeight()) {
                            continue;
                        }
                        if (best == null || Math.abs(mode.getRefreshRate() - wanted) < Math.abs(best.getRefreshRate() - wanted)) {
                            best = mode;
                        }
                    }
                    if (best != null) {
                        WindowManager.LayoutParams params = getWindow().getAttributes();
                        params.preferredDisplayModeId = best.getModeId();
                        params.preferredRefreshRate = best.getRefreshRate();
                        getWindow().setAttributes(params);
                    }
                } catch (Throwable ignored) {
                    // the current mode stays
                }
            }
        });
    }

    /**
     * Starts the app again in a new process: the engine switches between its Zombies and Multiplayer modes, or to
     * another mod, by starting over with other arguments (next_args.txt, read by the loader stub).
     */
    public void restartApp() {
        Intent intent = Intent.makeRestartActivityTask(getComponentName());
        startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    /**
     * The end of the log file (at most 120 000 bytes: an intent cannot carry much more) as text through the
     * system's share sheet.
     */
    public void shareLog(final String path) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    RandomAccessFile file = new RandomAccessFile(new File(path), "r");
                    long length = file.length();
                    int count = (int) Math.min(length, 120000L);
                    byte[] bytes = new byte[count];
                    file.seek(length - count);
                    file.readFully(bytes);
                    file.close();
                    text = new String(bytes, "UTF-8");
                } catch (Exception e) {
                    text = "The log could not be read: " + e;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Black Ops port log");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Send log"));
            }
        });
    }
}
