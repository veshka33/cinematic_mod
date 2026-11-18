package com.example.cinematic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses a music file. All vanilla 1.12.2 tracks (calm, hal, nuance, piano, creative) are by C418.
 * The assets folder is auto-detected (launchers like Prism/Freesm keep it outside the instance folder).
 */
public class MusicPicker {
    private static final String PREFIX = "minecraft/sounds/music/game/";
    public static String report = "";

    public static File pick(File mcDir) {
        String mode = CinematicConfig.musicMode.trim().toUpperCase(Locale.ROOT);
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder("musicMode=" + mode + "\n");
        report = sb.toString();
        if (mode.equals("NONE")) return null;

        if (mode.equals("CUSTOM")) {
            File[] files = new File(CinematicConfig.customMusicFolder).listFiles((d, n) -> {
                String l = n.toLowerCase(Locale.ROOT);
                return l.endsWith(".mp3") || l.endsWith(".ogg") || l.endsWith(".wav")
                        || l.endsWith(".flac") || l.endsWith(".m4a");
            });
            List<File> allowed = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    int dot = n.lastIndexOf('.');
                    if (!isBlacklisted(dot > 0 ? n.substring(0, dot) : n)) allowed.add(f);
                }
            }
            if (allowed.isEmpty()) { report = sb + "customMusicFolder is empty, missing or everything is blacklisted\n"; return null; }
            return allowed.get(rnd.nextInt(allowed.size()));
        }

        for (File assets : candidates(mcDir)) {
            Map<String, String> tracks = readTracks(assets);
            sb.append(assets).append(" -> ").append(tracks.size()).append(" tracks\n");
            if (tracks.isEmpty()) continue;
            File f = choose(assets, tracks, mode, rnd);
            if (f != null) { report = sb.toString(); return f; }
            sb.append("   (track files missing in objects/)\n");
        }
        report = sb.toString();
        return null;
    }

    private static boolean isBlacklisted(String name) {
        for (String b : CinematicConfig.musicBlacklist) {
            if (b.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static File choose(File assets, Map<String, String> tracks, String mode, Random rnd) {
        List<String> names = new ArrayList<>(tracks.keySet());
        if (mode.equals("SPECIFIC")) {
            names = new ArrayList<>();
            if (tracks.containsKey(CinematicConfig.specificTrack)) names.add(CinematicConfig.specificTrack);
        } else {
            if (CinematicConfig.allowedTracks.length > 0) names.retainAll(Arrays.asList(CinematicConfig.allowedTracks));
            names.removeIf(MusicPicker::isBlacklisted);
            names.removeIf(CinematicConfig.disabledTracks::contains);
        }
        Collections.shuffle(names, rnd);
        for (String n : names) {
            String hash = tracks.get(n);
            File f = new File(assets, "objects/" + hash.substring(0, 2) + "/" + hash);
            if (f.exists()) return f;
        }
        return null;
    }

    private static List<File> candidates(File mcDir) {
        LinkedHashSet<File> set = new LinkedHashSet<>();
        if (!CinematicConfig.assetsDir.trim().isEmpty()) set.add(new File(CinematicConfig.assetsDir.trim()));

        // --assetsDir from the launch arguments
        try {
            Object o = Launch.blackboard.get("ArgumentList");
            if (o instanceof List) {
                List<?> l = (List<?>) o;
                for (int i = 0; i < l.size() - 1; i++)
                    if ("--assetsDir".equals(String.valueOf(l.get(i)))) set.add(new File(String.valueOf(l.get(i + 1))));
            } else if (o instanceof Map) {
                Object v = ((Map<?, ?>) o).get("--assetsDir");
                if (v != null) set.add(new File(String.valueOf(v)));
            }
        } catch (Throwable ignored) { }
        try {
            String cmd = System.getProperty("sun.java.command", "");
            Matcher m = Pattern.compile("--assetsDir\\s+(\"[^\"]+\"|\\S+)").matcher(cmd);
            if (m.find()) set.add(new File(m.group(1).replace("\"", "")));
        } catch (Throwable ignored) { }

        // instance folder and all parents (launcher root usually has its own "assets")
        try {
            File d = mcDir.getCanonicalFile();
            for (int i = 0; i < 7 && d != null; i++, d = d.getParentFile()) set.add(new File(d, "assets"));
        } catch (Exception ignored) { }

        String appdata = System.getenv("APPDATA");
        if (appdata != null) set.add(new File(appdata, ".minecraft/assets"));
        set.add(new File(System.getProperty("user.home", "."), ".minecraft/assets"));
        return new ArrayList<>(set);
    }

    private static Map<String, String> readTracks(File assetsDir) {
        Map<String, String> res = new TreeMap<>();
        File idxDir = new File(assetsDir, "indexes");
        List<File> files = new ArrayList<>();
        File preferred = new File(idxDir, "1.12.json");
        if (preferred.exists()) files.add(preferred);
        File[] all = idxDir.listFiles((d, n) -> n.endsWith(".json"));
        if (all != null) files.addAll(Arrays.asList(all));

        for (File f : files) {
            try (Reader r = new FileReader(f)) {
                JsonObject objs = new JsonParser().parse(r).getAsJsonObject().getAsJsonObject("objects");
                for (Map.Entry<String, JsonElement> e : objs.entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith(PREFIX) && key.endsWith(".ogg")) {
                        String name = key.substring(key.lastIndexOf('/') + 1, key.length() - 4);
                        res.put(name, e.getValue().getAsJsonObject().get("hash").getAsString());
                    }
                }
                if (!res.isEmpty()) break;
            } catch (Exception ignored) { }
        }
        return res;
    }
}
