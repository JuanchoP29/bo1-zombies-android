package org.bo1port.zombies;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shared reader/manager for Zombies Checkpoint save slots (<files>/home/zm_saves/*.txt).
 */
public final class ZmSaveHelper {
    public static final class SlotInfo {
        public final String slotId;
        public final File file;
        public String map = "";
        public int round = 1;
        public int score = 500;
        public long timestamp = 0L;
        public int weaponCount = 0;
        public int perkCount = 0;

        SlotInfo(String slotId, File file) {
            this.slotId = slotId;
            this.file = file;
        }

        public String friendlyMap() {
            if ("zombie_theater".equalsIgnoreCase(map)) return "Kino der Toten";
            if ("zombie_pentagon".equalsIgnoreCase(map)) return "Five";
            if ("zombie_cosmodrome".equalsIgnoreCase(map)) return "Ascension";
            if ("zombie_coast".equalsIgnoreCase(map)) return "Call of the Dead";
            if ("zombie_temple".equalsIgnoreCase(map)) return "Shangri-La";
            if ("zombie_moon".equalsIgnoreCase(map)) return "Moon";
            if ("zombie_cod5_prototype".equalsIgnoreCase(map)) return "Nacht der Untoten";
            if ("zombie_cod5_asylum".equalsIgnoreCase(map)) return "Verrückt";
            if ("zombie_cod5_sumpf".equalsIgnoreCase(map)) return "Shi No Numa";
            if ("zombie_cod5_factory".equalsIgnoreCase(map)) return "Der Riese";
            if ("zombietron".equalsIgnoreCase(map)) return "Dead Ops Arcade";
            return map.isEmpty() ? "Desconocido" : map;
        }

        public String formattedDate() {
            long ms = timestamp > 0 ? (timestamp * 1000L) : file.lastModified();
            return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(ms));
        }

        public String summaryLine() {
            return String.format(Locale.US, "Ronda %d · %d pts · %d armas · %d perks · %s",
                    round, score, weaponCount, perkCount, formattedDate());
        }
    }

    private ZmSaveHelper() {}

    public static File savesDir(File filesDir) {
        File dir = new File(new File(filesDir, "home"), "zm_saves");
        dir.mkdirs();
        return dir;
    }

    public static List<SlotInfo> listSaves(File filesDir) {
        List<SlotInfo> list = new ArrayList<SlotInfo>();
        File dir = savesDir(filesDir);
        File[] files = dir.listFiles();
        if (files == null) {
            return list;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".txt")) {
                continue;
            }
            String id = f.getName().substring(0, f.getName().length() - 4);
            SlotInfo info = parse(id, f);
            if (info != null && !info.map.isEmpty()) {
                list.add(info);
            }
        }
        return list;
    }

    private static SlotInfo parse(String id, File f) {
        SlotInfo info = new SlotInfo(id, f);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("map=")) {
                    info.map = line.substring(4);
                } else if (line.startsWith("round=")) {
                    info.round = Integer.parseInt(line.substring(6));
                } else if (line.startsWith("score=")) {
                    info.score = Integer.parseInt(line.substring(6));
                } else if (line.startsWith("time=")) {
                    info.timestamp = Long.parseLong(line.substring(5));
                } else if (line.startsWith("weapon=")) {
                    info.weaponCount++;
                } else if (line.startsWith("perk=")) {
                    info.perkCount++;
                }
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean deleteSlot(File filesDir, String slotId) {
        File f = new File(savesDir(filesDir), slotId + ".txt");
        return f.exists() && f.delete();
    }
}
