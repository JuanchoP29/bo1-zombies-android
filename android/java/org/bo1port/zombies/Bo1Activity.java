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
    protected void onStop() {
        setControllerRumble("0 0");
        if (touchOverlay != null) {
            touchOverlay.stopOverlay();
        }
        if (secondScreen != null) {
            secondScreen.stop();
        }
        super.onStop();
    }

    private boolean rumbleLogged;
    private boolean rumbleEnabled = true;
    private int lastRumbleLow = -1;
    private int lastRumbleHigh = -1;
    private long lastRumbleAtMs = 0L;

    /**
     * Native Android gamepad rumble (SDL2's ANDROID_JoystickRumble returns SDL_Unsupported for Bluetooth pads).
     * Rate-limited (100 ms) and quantized to prevent Bluetooth HID write queue overflows (HID_HostWriteDev status:8)
     * that cause DualShock 4 / Bluetooth controllers to disconnect mid-match.
     */
    public void setControllerRumble(String spec) {
        try {
            if (spec == null) {
                return;
            }
            SharedPreferences sp = getSharedPreferences("bo1_launcher_prefs", MODE_PRIVATE);
            rumbleEnabled = sp.getInt("padRumble", 1) != 0;
            String[] parts = spec.trim().split("\\s+");
            if (parts.length < 2) {
                return;
            }
            int low = rumbleEnabled ? Math.max(0, Math.min(255, Integer.parseInt(parts[0]))) : 0;
            int high = rumbleEnabled ? Math.max(0, Math.min(255, Integer.parseInt(parts[1]))) : 0;

            long now = SystemClock.uptimeMillis();
            boolean stopping = (low == 0 && high == 0);
            if (stopping && lastRumbleLow == 0 && lastRumbleHigh == 0) {
                return;
            }
            if (!stopping) {
                boolean smallDelta = Math.abs(low - lastRumbleLow) < 16 && Math.abs(high - lastRumbleHigh) < 16;
                if (smallDelta && (now - lastRumbleAtMs) < 220L) {
                    return;
                }
                if ((now - lastRumbleAtMs) < 100L) {
                    return;
                }
            }
            lastRumbleLow = low;
            lastRumbleHigh = high;
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
                                if (!rumbleLogged && (low > 0 || high > 0)) {
                                    rumbleLogged = true;
                                    Log.i("BO1Rumble", "Using VibratorManager on '" + dev.getName()
                                            + "' (pass=" + pass + ", motors=" + vibIds.length
                                            + ", low=" + low + ", high=" + high + ")");
                                }
                                if (low <= 0 && high <= 0) {
                                    vm.cancel();
                                } else if (vibIds.length >= 2) {
                                    CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
                                    if (low > 0) {
                                        combo.addVibrator(vibIds[0], VibrationEffect.createOneShot(350, low));
                                    }
                                    if (high > 0) {
                                        combo.addVibrator(vibIds[1], VibrationEffect.createOneShot(350, high));
                                    }
                                    vm.vibrate(combo.combine());
                                } else {
                                    int amp = Math.max(low, high);
                                    vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(350, amp)));
                                }
                                return;
                            }
                        }
                    }
                    @SuppressWarnings("deprecation")
                    Vibrator vib = dev.getVibrator();
                    if (vib != null && vib.hasVibrator()) {
                        if (!rumbleLogged && (low > 0 || high > 0)) {
                            rumbleLogged = true;
                            Log.i("BO1Rumble", "Using legacy Vibrator on '" + dev.getName()
                                    + "' (pass=" + pass + ", low=" + low + ", high=" + high + ")");
                        }
                        int amp = Math.max(low, high);
                        if (amp <= 0) {
                            vib.cancel();
                        } else {
                            vib.vibrate(VibrationEffect.createOneShot(350, amp));
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
