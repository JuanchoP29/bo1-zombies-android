package org.bo1port.zombies;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Experimental Root Bypass Charging ("Carga Directa") helper.
 * OFF by default: never invokes `su` unless the user explicitly enables Bypass Charging.
 * When active on a rooted device (e.g. POCO F5 mondrian via KernelSU), disables battery charging while keeping
 * charger power flowing to the board, and ALWAYS restores normal charging on pause/stop/destroy.
 */
public final class BypassChargingHelper {
    private static final String[][] NODES = new String[][] {
            // { path, enableBypassValue, restoreNormalValue, friendlyName }
            { "/sys/class/qcom-battery/night_charging", "1", "0", "qcom night_charging" },
            { "/sys/class/qcom-battery/charging_enabled", "0", "1", "qcom charging_enabled" },
            { "/sys/class/power_supply/battery/charging_enabled", "0", "1", "battery charging_enabled" },
            { "/sys/class/power_supply/battery/battery_charging_enabled", "0", "1", "battery_charging_enabled" },
            { "/sys/class/power_supply/battery/mmi_charging_enable", "0", "1", "mmi_charging_enable" },
            { "/sys/class/power_supply/battery/batt_slate_mode", "1", "0", "batt_slate_mode" },
    };

    private static volatile String activeNodePath = null;
    private static volatile String activeRestoreVal = null;
    private static volatile String activeNodeLabel = null;
    private static volatile String lastError = null;

    private BypassChargingHelper() {}

    public static boolean isActive() {
        return activeNodePath != null;
    }

    public static String getActiveLabel() {
        return activeNodeLabel;
    }

    public static String getLastError() {
        return lastError;
    }

    /** Attempts to enable hardware bypass charging via `su`. Returns true on success. */
    public static synchronized boolean enable() {
        if (activeNodePath != null) {
            return true;
        }
        lastError = null;
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < NODES.length; i++) {
            String path = NODES[i][0];
            String onVal = NODES[i][1];
            script.append("if [ -w \"").append(path).append("\" ]; then ")
                  .append("echo ").append(onVal).append(" > \"").append(path).append("\" 2>/dev/null && ")
                  .append("echo \"BYPASS_OK:").append(i).append("\" && exit 0; fi\n");
        }
        script.append("echo \"BYPASS_NONE\"\n");

        String out = runSu(script.toString());
        if (out == null) {
            lastError = "Sin permiso Root (concede acceso en KernelSU/Magisk)";
            return false;
        }
        int idx = out.indexOf("BYPASS_OK:");
        if (idx >= 0) {
            try {
                String num = out.substring(idx + 10).trim().split("\\s+")[0];
                int n = Integer.parseInt(num);
                if (n >= 0 && n < NODES.length) {
                    activeNodePath = NODES[n][0];
                    activeRestoreVal = NODES[n][2];
                    activeNodeLabel = NODES[n][3];
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        lastError = "Este kernel no expone un nodo de bypass compatible";
        return false;
    }

    /** ALWAYS restores normal battery charging if bypass was enabled. */
    public static synchronized void restore() {
        String path = activeNodePath;
        String val = activeRestoreVal;
        activeNodePath = null;
        activeRestoreVal = null;
        activeNodeLabel = null;
        if (path != null && val != null) {
            runSu("echo " + val + " > \"" + path + "\" 2>/dev/null\n");
        }
    }

    /** Non-root battery telemetry: level %, temp °C, and live current in mA. */
    public static String getBatteryTelemetry(Context ctx) {
        try {
            Intent batt = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            int tempTenths = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
            float tempC = tempTenths / 10.0f;

            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int uA = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
            int mA = Math.abs(uA) > 10000 ? (uA / 1000) : uA;

            String state = isActive()
                    ? ("Bypass ACTIVO (" + activeNodeLabel + ")")
                    : (lastError != null ? lastError : "Carga normal");
            return String.format(Locale.US, "Batería: %d%% · %.1f °C · %+d mA\nEstado: %s",
                    pct, tempC, mA, state);
        } catch (Exception e) {
            return "Estado: " + (isActive() ? ("Bypass ACTIVO (" + activeNodeLabel + ")") : "Carga normal");
        }
    }

    private static String runSu(String commands) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(commands);
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            int code = p.waitFor();
            if (code != 0 && sb.length() == 0) {
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
