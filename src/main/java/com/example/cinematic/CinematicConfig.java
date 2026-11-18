package com.example.cinematic;

import net.minecraftforge.common.config.Configuration;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class CinematicConfig {
    public static Configuration config;

    /** All vanilla 1.12.2 music tracks (all by C418). Each one gets an on/off switch in the config screen. */
    public static final String[] ALL_TRACKS = {
            "calm1", "calm2", "calm3", "hal1", "hal2", "hal3", "hal4", "nuance1", "nuance2",
            "piano1", "piano2", "piano3", "creative1", "creative2", "creative3", "creative4", "creative5", "creative6"};
    public static final Set<String> disabledTracks = new HashSet<>();

    public static int shortsWidth, hotkey, clipCount, clipSeconds, fps, minDistance, maxDistance, maxAttempts,
            moveBlocks, cameraHeight, minSpotDistance, mineGlideBlocks;
    public static double minScore, musicVolume, fadeSeconds, maxWaterRatio, structureChance;
    public static String barsAspect, outputFormat, ffmpegPath, assetsDir, musicMode, specificTrack,
            customMusicFolder, outputDir, timeOfDay, weather, structureCamera, structureView;
    public static String[] allowedTracks, biomeWhitelist, biomeBlacklist, structures, musicBlacklist;
    public static boolean keepClips, randomMusicOffset, clipLoop, allowForceStop,
            avoidStructures, rememberSpots, restoreEnvironment;

    public static void init(File file) {
        config = new Configuration(file);
        load();
    }

    /** Reads every value from the Configuration (also called after the in-game config screen is closed). */
    public static void load() {
        Configuration c = config;
        String g = "general", e = "environment", s = "spots", st = "structures", m = "music";

        // ---- general
        hotkey = c.getInt("hotkey", g, Keyboard.KEY_F1, 1, 255, "LWJGL key code (59 = F1)");
        clipCount = c.getInt("clipCount", g, 5, 1, 30, "How many clips to record");
        clipSeconds = c.getInt("clipSeconds", g, 7, 2, 60, "Seconds per clip");
        fps = c.getInt("fps", g, 30, 10, 60, "Video FPS");
        ffmpegPath = c.getString("ffmpegPath", g, "ffmpeg", "Path to ffmpeg.exe (or just 'ffmpeg' if it is in PATH)");
        outputDir = c.getString("outputDir", g, "cinematic", "Output folder inside .minecraft");
        outputFormat = c.getString("outputFormat", g, "VERTICAL_BARS",
                "VERTICAL_BARS = 9:16, picture in the middle with black bars; VERTICAL = 9:16 center crop; "
                        + "VERTICAL_BLUR = blurred 9:16 background; LANDSCAPE = original",
                new String[]{"VERTICAL_BARS", "VERTICAL", "VERTICAL_BLUR", "LANDSCAPE"});
        barsAspect = c.getString("barsAspect", g, "1:1", "VERTICAL_BARS: aspect ratio of the picture in the middle",
                new String[]{"1:1", "4:3", "3:2", "16:9"});
        shortsWidth = c.getInt("shortsWidth", g, 1080, 360, 2160, "Width of the vertical video (1080 -> 1080x1920)");
        fadeSeconds = c.get(g, "fadeSeconds", 0.7, "Fade to black at the start/end of every clip (seconds, 0 = off)").getDouble();
        clipLoop = c.getBoolean("clipLoop", g, false, "true = keep making videos endlessly; the hotkey finishes the current video and then stops");
        allowForceStop = c.getBoolean("allowForceStop", g, false, "false = the hotkey ONLY stops after clipCount clips are done; true = a 2nd press stops immediately (unfinished clips are deleted)");
        keepClips = c.getBoolean("keepClips", g, false, "Keep the separate clip files after merging");

        // ---- environment (item: time of day + weather)
        timeOfDay = c.getString("timeOfDay", e, "GOLDEN",
                "Time of day for every clip. GOLDEN = random sunrise or sunset, RANDOM = sunrise/sunset/day/night, OFF = don't touch",
                new String[]{"OFF", "SUNRISE", "SUNSET", "GOLDEN", "DAY", "NIGHT", "RANDOM"});
        weather = c.getString("weather", e, "CLEAR", "Weather for every clip (OFF = don't touch)",
                new String[]{"OFF", "CLEAR", "RAIN"});
        restoreEnvironment = c.getBoolean("restoreEnvironment", e, true, "Put the original time, weather and daylight cycle back when recording ends");

        // ---- spots
        minDistance = c.getInt("minDistance", s, 300, 50, 20000, "Min distance of a spot from where you started");
        maxDistance = c.getInt("maxDistance", s, 1500, 100, 30000, "Max distance of a spot from where you started");
        minScore = c.get(s, "minScore", 5.5, "Minimum 'beauty' score of a spot (0..11)").getDouble();
        maxAttempts = c.getInt("maxAttempts", s, 10, 1, 50, "Attempts per clip to find a good spot");
        moveBlocks = c.getInt("moveBlocks", s, 0, 0, 200, "How far the camera glides forward during a clip (0 = static camera)");
        cameraHeight = c.getInt("cameraHeight", s, 6, 1, 60, "Camera height above the ground");
        biomeWhitelist = c.getStringList("biomeWhitelist", s, new String[0],
                "Only film in biomes whose name contains one of these words (empty = any). Examples: forest, taiga, mesa, savanna, jungle, ice");
        biomeBlacklist = c.getStringList("biomeBlacklist", s, new String[]{"ocean"},
                "Never film in biomes whose name contains one of these words. Examples: ocean, river, swamp, desert");
        maxWaterRatio = c.get(s, "maxWaterRatio", 0.6, "Reject spots where more than this part (0.0 - 1.0) of the view is water").getDouble();
        avoidStructures = c.getBoolean("avoidStructures", s, true, "Reject spots with villages / buildings in view");
        rememberSpots = c.getBoolean("rememberSpots", s, true, "Remember filmed places (per world) and don't film near them again");
        minSpotDistance = c.getInt("minSpotDistance", s, 250, 20, 5000, "Minimum distance between two filmed places (blocks)");

        // ---- structures (villages, temples, mansions, monuments, mineshafts)
        structureChance = c.get(st, "structureChance", 0.35,
                "Chance (0.0 - 1.0) that a clip is filmed at a structure instead of plain nature").getDouble();
        structures = c.getStringList("structures", st, new String[]{"Village", "Temple", "Mansion", "Mineshaft"},
                "Structures to film (found with /locate). Allowed: Village, Temple, Mansion, Monument, Mineshaft");
        structureCamera = c.getString("structureCamera", st, "ORBIT",
                "ORBIT = camera slowly circles the structure; STATIC = fixed camera inside or above the structure, different angle every clip",
                new String[]{"ORBIT", "STATIC"});
        structureView = c.getString("structureView", st, "RANDOM",
                "Only for STATIC: INSIDE = among/inside the buildings, ABOVE = high above the structure, RANDOM = either",
                new String[]{"RANDOM", "INSIDE", "ABOVE"});
        mineGlideBlocks = c.getInt("mineGlideBlocks", st, 8, 0, 16, "Mineshaft: how far the camera glides along the tunnel (0 = static)");

        // ---- one switch per vanilla track (false = never used in RANDOM mode)
        disabledTracks.clear();
        for (String t : ALL_TRACKS) {
            if (!c.getBoolean(t, "music_tracks", true, "true = '" + t + "' may be used in RANDOM mode, false = never use it")) {
                disabledTracks.add(t);
            }
        }
        c.setCategoryComment("music_tracks", "Switch off the C418 tracks you do not want in your videos");

        // ---- music
        musicMode = c.getString("musicMode", m, "RANDOM", "RANDOM (vanilla C418 tracks), SPECIFIC (specificTrack), CUSTOM (customMusicFolder), NONE",
                new String[]{"RANDOM", "SPECIFIC", "CUSTOM", "NONE"});
        specificTrack = c.getString("specificTrack", m, "calm1", "Vanilla track for SPECIFIC mode",
                new String[]{"calm1", "calm2", "calm3", "hal1", "hal2", "hal3", "hal4", "nuance1", "nuance2",
                        "piano1", "piano2", "piano3", "creative1", "creative2", "creative3", "creative4", "creative5", "creative6"});
        allowedTracks = c.getStringList("allowedTracks", m, new String[0], "Advanced: for RANDOM only pick from these tracks (empty = all). Easier: use the switches in the music_tracks section");
        musicBlacklist = c.getStringList("musicBlacklist", m, new String[0],
                "Advanced: names that must NEVER be used, mainly for CUSTOM mode (file names without extension). For vanilla tracks use the switches in the music_tracks section");
        customMusicFolder = c.getString("customMusicFolder", m, "", "Folder with your own mp3/ogg/wav/flac files");
        musicVolume = c.get(m, "musicVolume", 0.8, "Music volume 0.0 - 2.0").getDouble();
        randomMusicOffset = c.getBoolean("randomMusicOffset", m, true, "Start the track from a random position");
        assetsDir = c.getString("assetsDir", m, "", "Minecraft assets folder (empty = auto-detect)");

        if (c.hasChanged()) c.save();
    }
}
