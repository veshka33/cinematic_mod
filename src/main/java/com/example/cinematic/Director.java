package com.example.cinematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.GameType;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** State machine: F1 -> spectator -> (find spot -> warm up -> record 7s) x5 -> merge. */
public class Director {
    private enum State { IDLE, WAIT_CHUNKS, LOCATE_WAIT, WARMUP, RECORD }
    private enum Cam { GLIDE, ORBIT }

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private State state = State.IDLE;
    private int clipIndex, attempts, waitTicks, hudFix;
    private boolean stopAfterVideo;
    private final SpotMemory memory = new SpotMemory();
    private long origTime;
    private boolean origRain, origDaylight = true, daylightFrozen, timeChanged, weatherChanged;
    private String batchTag = "";

    // structure clips
    private String structureType;                 // null = plain nature clip
    private boolean structurePending, locateReady, locateFailed, nightVisionNeeded, nightVisionOn;
    private int locX, locZ;

    // camera
    private Cam camMode = Cam.GLIDE;
    private boolean underground;
    private double glideBlocks, fixedY, ocx, ocy, ocz, orbitR, orbitAng0, orbitArc;
    private float camPitch = 12f;
    private double tx, tz;
    private SpotFinder.Spot spot;
    private double camX, camY, camZ, dirX, dirZ;
    private float camYaw, yawDrift;
    private long recStart;
    private ClipRecorder recorder;
    private final List<ClipRecorder> recorders = new ArrayList<>();

    private GameType origType;
    private double ox, oy, oz;
    private float oyaw, opitch;

    // ---------------------------------------------------------------- input

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (CinematicMod.OPEN_CONFIG != null && CinematicMod.OPEN_CONFIG.isPressed()) {
            if (state == State.IDLE) mc.displayGuiScreen(new ConfigGuiFactory.ConfigGui(null));
            return;
        }
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == CinematicConfig.hotkey) {
            if (state == State.IDLE) start();
            else if (!stopAfterVideo) stopAfterVideo = true;   // 1st press: finish the current video, then stop
            else if (CinematicConfig.allowForceStop) abort("Stopped."); // 2nd press: only if allowed in the config
        }
    }

    private void start() {
        EntityPlayerSP p = mc.player;
        if (p == null || mc.world == null) return;
        if (mc.world.provider.getDimension() != 0) { chat("Only works in the Overworld."); return; }
        if (!VideoMerger.ffmpegOk(CinematicConfig.ffmpegPath)) {
            chat("ffmpeg not found. Install it and/or set ffmpegPath in config/cinematicrec.cfg"); return;
        }
        origType = mc.playerController.getCurrentGameType();
        ox = p.posX; oy = p.posY; oz = p.posZ; oyaw = p.rotationYaw; opitch = p.rotationPitch;
        recorders.clear();
        origTime = mc.world.getWorldTime();
        origRain = mc.world.isRaining();
        origDaylight = mc.getIntegratedServer() == null
                || mc.getIntegratedServer().getWorld(0).getGameRules().getBoolean("doDaylightCycle");
        daylightFrozen = timeChanged = weatherChanged = false;
        File outDir = new File(mc.mcDataDir, CinematicConfig.outputDir);
        outDir.mkdirs();
        memory.load(outDir, worldKey());
        clipIndex = 0;
        attempts = 0;
        stopAfterVideo = false;
        batchTag = newTag();
        p.sendChatMessage("/gamemode spectator");
        planClip();
    }

    // ---------------------------------------------------------------- spot search

    /** Decides what the next clip will be: plain nature or a structure. */
    private void planClip() {
        attempts = 0;
        structureType = null;
        structurePending = false;
        String[] list = CinematicConfig.structures;
        if (CinematicConfig.structureChance > 0 && list.length > 0 && rnd.nextDouble() < CinematicConfig.structureChance) {
            structureType = canonicalStructure(list[rnd.nextInt(list.length)]);
            structurePending = structureType != null;
        }
        nextSpot();
    }

    private static String canonicalStructure(String name) {
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "village":   return "Village";
            case "temple":    return "Temple";
            case "mansion":   return "Mansion";
            case "monument":  return "Monument";
            case "mineshaft": return "Mineshaft";
            default:          return null;
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (state != State.LOCATE_WAIT) return;
        ITextComponent m = e.getMessage();
        if (!(m instanceof TextComponentTranslation)) return;
        String key = ((TextComponentTranslation) m).getKey();
        if ("commands.locate.success".equals(key)) {
            Matcher mt = Pattern.compile("-?\\d+").matcher(m.getUnformattedText());
            int[] v = new int[2];
            int n = 0;
            while (n < 2 && mt.find()) v[n++] = Integer.parseInt(mt.group());
            if (n == 2) { locX = v[0]; locZ = v[1]; locateReady = true; }
        } else if ("commands.locate.failure".equals(key)) {
            locateFailed = true;
        }
    }

    /** Sets up the camera for a structure clip. Returns false if nothing usable was found. */
    private boolean frameStructure() {
        nightVisionNeeded = false;
        if (structureType.equals("Mineshaft")) {
            StructureFinder.Corridor c = StructureFinder.findMineshaft(mc.world, (int) tx, (int) tz, rnd);
            if (!c.valid) return false;
            camMode = Cam.GLIDE;
            underground = true;
            fixedY = c.y;
            camY = c.y;
            camX = c.x;
            camZ = c.z;
            camYaw = c.yaw;
            camPitch = 2f;
            double rad = Math.toRadians(camYaw);
            dirX = -Math.sin(rad);
            dirZ = Math.cos(rad);
            glideBlocks = Math.max(0, Math.min(CinematicConfig.mineGlideBlocks, c.freeLen - 3));
            yawDrift = 0;
            nightVisionNeeded = true;
            return true;
        }
        StructureFinder.Anchor a = StructureFinder.findSurface(mc.world, (int) tx, (int) tz, structureType);
        if (!a.valid) return false;
        if ("STATIC".equalsIgnoreCase(CinematicConfig.structureCamera.trim())) return frameStaticView(a);
        camMode = Cam.ORBIT;
        underground = structureType.equals("Monument");
        ocx = a.cx; ocy = a.cy; ocz = a.cz; orbitR = a.radius;
        orbitAng0 = rnd.nextDouble() * Math.PI * 2;
        orbitArc = (rnd.nextBoolean() ? 1 : -1) * Math.toRadians(70);
        double x0 = ocx + orbitR * Math.cos(orbitAng0), z0 = ocz + orbitR * Math.sin(orbitAng0);
        camY = underground ? ocy + 6 : Math.max(groundAt(x0, z0) + CinematicConfig.cameraHeight + 2, ocy + 8);
        nightVisionNeeded = underground;
        return true;
    }

    /**
     * STATIC structure view: the camera does not move. It stands inside the buildings or high above them,
     * and the angle is different every clip. (underground = "fixed height" here: no terrain following.)
     */
    private boolean frameStaticView(StructureFinder.Anchor a) {
        camMode = Cam.GLIDE;
        glideBlocks = 0;
        yawDrift = 0;
        underground = true;
        nightVisionNeeded = false;
        boolean monument = structureType.equals("Monument");
        String view = CinematicConfig.structureView.trim().toUpperCase(Locale.ROOT);
        boolean inside = !monument && (view.equals("INSIDE") || (view.equals("RANDOM") && rnd.nextBoolean()));

        if (inside) {
            StructureFinder.Corridor c = StructureFinder.findInterior(mc.world, a, rnd);
            if (c.valid) {
                camX = c.x; camZ = c.z; fixedY = c.y; camY = c.y;
                camYaw = c.yaw;
                camPitch = 3f;
                nightVisionNeeded = true;     // rooms are dark
                return true;
            }
            // no room found: fall through to the aerial view
        }

        double ang = rnd.nextDouble() * Math.PI * 2;
        if (monument) {                                   // underwater, oblique
            double r = a.radius * 0.7;
            camX = a.cx + r * Math.cos(ang);
            camZ = a.cz + r * Math.sin(ang);
            fixedY = a.cy + 10;
            lookAt(a.cx, a.cy, a.cz);
            nightVisionNeeded = true;
        } else if (rnd.nextInt(3) == 0) {                 // straight down
            camX = a.cx;
            camZ = a.cz;
            fixedY = a.cy + 35;
            camYaw = rnd.nextFloat() * 360f;
            camPitch = 85f;
        } else {                                          // oblique aerial view
            double r = a.radius * 0.9;
            camX = a.cx + r * Math.cos(ang);
            camZ = a.cz + r * Math.sin(ang);
            fixedY = a.cy + 22 + rnd.nextInt(18);
            lookAt(a.cx, a.cy + 2, a.cz);
        }
        camY = fixedY;
        return true;
    }

    private void lookAt(double x, double y, double z) {
        double dx = x - camX, dz = z - camZ, dy = y - fixedY;
        camYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        camPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void nextSpot() {
        attempts++;
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = CinematicConfig.minDistance + rnd.nextDouble() * (CinematicConfig.maxDistance - CinematicConfig.minDistance);
            tx = ox + Math.cos(ang) * d;
            tz = oz + Math.sin(ang) * d;
            if (!CinematicConfig.rememberSpots || !memory.isNear(tx, tz)) break;
        }
        mc.player.sendChatMessage(String.format(Locale.ROOT, "/tp @s %.1f 200 %.1f", tx, tz));
        state = State.WAIT_CHUNKS;
        waitTicks = 0;
    }

    private boolean chunksLoaded(int bx, int bz) {
        int r = Math.min(3, Math.max(1, mc.gameSettings.renderDistanceChunks - 1));
        int cx = bx >> 4, cz = bz >> 4;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++)
                if (mc.world.getChunkFromChunkCoords(cx + x, cz + z).isEmpty()) return false;
        return true;
    }

    private double groundAt(double x, double z) {
        return mc.world.getHeight(new BlockPos(x, 0, z)).getY();
    }

    // ---------------------------------------------------------------- tick logic

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (hudFix > 0) { mc.gameSettings.hideGUI = false; hudFix--; }
        if (state == State.IDLE) return;
        if (mc.world == null || mc.player == null) { abort("World closed."); return; }

        mc.gameSettings.hideGUI = true;

        switch (state) {
            case WAIT_CHUNKS:
                waitTicks++;
                if (waitTicks == 40 && mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                    abort("Could not switch to spectator: cheats / operator rights are required.");
                    return;
                }
                boolean near = Math.abs(mc.player.posX - tx) < 8 && Math.abs(mc.player.posZ - tz) < 8;
                if (near && structurePending) {            // arrived at a random point: ask where the nearest structure is
                    structurePending = false;
                    locateReady = locateFailed = false;
                    mc.player.sendChatMessage("/locate " + structureType);
                    state = State.LOCATE_WAIT;
                    waitTicks = 0;
                    break;
                }
                if (near && chunksLoaded((int) tx, (int) tz)) {
                    if (structureType != null) {
                        if (frameStructure()) enterWarmup();
                        else { structureType = null; nextSpot(); }
                        break;
                    }
                    spot = SpotFinder.evaluate(mc.world, (int) tx, (int) tz);
                    boolean good = !spot.rejected && spot.score >= CinematicConfig.minScore;
                    if (good || (!spot.rejected && attempts >= CinematicConfig.maxAttempts)) beginWarmup();
                    else if (attempts >= CinematicConfig.maxAttempts * 3)
                        abort("Could not find a suitable spot. Relax biome / water / structure settings.");
                    else nextSpot();
                } else if (waitTicks > 400) {
                    if (attempts >= CinematicConfig.maxAttempts * 2) abort("Could not load chunks.");
                    else nextSpot();
                }
                break;

            case LOCATE_WAIT:
                waitTicks++;
                if (locateReady) {
                    locateReady = false;
                    if (CinematicConfig.rememberSpots && memory.isNear(locX, locZ)) {   // already filmed: try another point
                        if (attempts < CinematicConfig.maxAttempts) structurePending = true; else structureType = null;
                        nextSpot();
                    } else {
                        tx = locX;
                        tz = locZ;
                        mc.player.sendChatMessage(String.format(Locale.ROOT, "/tp @s %.1f 200 %.1f", tx, tz));
                        state = State.WAIT_CHUNKS;
                        waitTicks = 0;
                    }
                } else if (locateFailed || waitTicks > 400) {
                    structureType = null;
                    nextSpot();
                }
                break;

            case WARMUP:
                if (++waitTicks >= 50) startRecording(); // ~2.5s so chunks get rendered
                break;

            default:
                break;
        }
    }

    private void beginWarmup() {
        camMode = Cam.GLIDE;
        underground = false;
        nightVisionNeeded = false;
        camPitch = 12f;
        glideBlocks = CinematicConfig.moveBlocks;
        camX = tx; camZ = tz;
        camYaw = spot.yaw;
        double rad = Math.toRadians(camYaw);
        dirX = -Math.sin(rad);
        dirZ = Math.cos(rad);
        yawDrift = glideBlocks > 0 ? (rnd.nextBoolean() ? 1 : -1) * (8 + rnd.nextInt(10)) : 0;
        camY = groundAt(camX, camZ) + CinematicConfig.cameraHeight;
        enterWarmup();
    }

    private void enterWarmup() {
        applyEnvironment();
        if (nightVisionNeeded) {
            mc.player.sendChatMessage("/effect @s minecraft:night_vision 60 0 true");
            nightVisionOn = true;
        }
        state = State.WARMUP;
        waitTicks = 0;
    }

    private void startRecording() {
        try {
            File dir = new File(mc.mcDataDir, CinematicConfig.outputDir);
            dir.mkdirs();
            File f = new File(dir, "clip_" + batchTag + "_" + (clipIndex + 1) + ".mp4");
            recorder = new ClipRecorder(CinematicConfig.ffmpegPath, f,
                    mc.getFramebuffer().framebufferTextureWidth,
                    mc.getFramebuffer().framebufferTextureHeight,
                    CinematicConfig.fps);
            recorders.add(recorder);
            recStart = System.nanoTime();
            state = State.RECORD;
        } catch (IOException ex) {
            abort("Could not start ffmpeg: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- per-frame camera + capture

    private double progress() {
        if (state != State.RECORD) return 0;
        return Math.min(1.0, (System.nanoTime() - recStart) / (CinematicConfig.clipSeconds * 1e9));
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (state != State.WARMUP && state != State.RECORD) return;
        EntityPlayerSP p = mc.player;
        if (p == null || mc.world == null) return;

        double t = progress();
        if (e.phase == TickEvent.Phase.START) {
            double s = t * t * (3 - 2 * t); // smoothstep
            double x, z;
            float yaw, pitch;
            if (camMode == Cam.ORBIT) {
                double th = orbitAng0 + orbitArc * s;
                x = ocx + orbitR * Math.cos(th);
                z = ocz + orbitR * Math.sin(th);
                double targetY = underground ? ocy + 6
                        : Math.max(groundAt(x, z) + CinematicConfig.cameraHeight + 2, ocy + 8);
                camY += (targetY - camY) * 0.08;
                double dx = ocx - x, dz = ocz - z;
                double dy = (ocy + (underground ? 0 : 3)) - camY;
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            } else {
                x = camX + dirX * glideBlocks * s;
                z = camZ + dirZ * glideBlocks * s;
                double targetY = underground ? fixedY : groundAt(x, z) + CinematicConfig.cameraHeight;
                camY += (targetY - camY) * 0.08;
                yaw = camYaw + (float) (yawDrift * s);
                pitch = camPitch;
            }
            p.setLocationAndAngles(x, camY, z, yaw, pitch);
            p.rotationYawHead = yaw;
            p.motionX = p.motionY = p.motionZ = 0;
        } else if (state == State.RECORD) {
            recorder.addFrame(mc.getFramebuffer());
            if (t >= 1.0) finishClip();
        }
    }

    private void finishClip() {
        recorder.finish();
        recorder = null;
        if (CinematicConfig.rememberSpots) memory.add(tx, tz);
        removeNightVision();
        clipIndex++;
        if (clipIndex >= CinematicConfig.clipCount) {
            finishAll();
        } else {
            planClip();
        }
    }

    // ---------------------------------------------------------------- finish / abort

    private void restorePlayer() {
        state = State.IDLE;
        stopAfterVideo = false;
        hudFix = 5;
        EntityPlayerSP p = mc.player;
        if (p == null) return;
        removeNightVision();
        restoreEnvironment(p);
        p.sendChatMessage("/gamemode " + origType.getName());
        p.sendChatMessage(String.format(Locale.ROOT, "/tp @s %.2f %.2f %.2f %.1f %.1f", ox, oy, oz, oyaw, opitch));
    }

    // ---------------------------------------------------------------- time of day / weather

    private void removeNightVision() {
        if (nightVisionOn && mc.player != null) mc.player.sendChatMessage("/effect @s minecraft:night_vision 0");
        nightVisionOn = false;
    }

    private int pickTime() {
        switch (CinematicConfig.timeOfDay.trim().toUpperCase(Locale.ROOT)) {
            case "SUNRISE": return 23500;
            case "SUNSET":  return 12300;
            case "DAY":     return 6000;
            case "NIGHT":   return 18000;
            case "GOLDEN":  return rnd.nextBoolean() ? 23500 : 12300;
            case "RANDOM":  return new int[]{23500, 12300, 6000, 18000}[rnd.nextInt(4)];
            default:        return -1;
        }
    }

    private void applyEnvironment() {
        EntityPlayerSP p = mc.player;
        if (p == null) return;
        int t = pickTime();
        if (t >= 0) {
            if (!daylightFrozen) { p.sendChatMessage("/gamerule doDaylightCycle false"); daylightFrozen = true; }
            p.sendChatMessage("/time set " + t);
            timeChanged = true;
        }
        String w = CinematicConfig.weather.trim().toUpperCase(Locale.ROOT);
        if (w.equals("CLEAR")) { p.sendChatMessage("/weather clear 600"); weatherChanged = true; }
        else if (w.equals("RAIN")) { p.sendChatMessage("/weather rain 600"); weatherChanged = true; }
    }

    private void restoreEnvironment(EntityPlayerSP p) {
        if (CinematicConfig.restoreEnvironment) {
            if (timeChanged) p.sendChatMessage("/time set " + (int) Math.min(origTime, Integer.MAX_VALUE));
            if (weatherChanged) p.sendChatMessage(origRain ? "/weather rain 600" : "/weather clear 600");
            if (daylightFrozen) p.sendChatMessage("/gamerule doDaylightCycle " + origDaylight);
        } else if (daylightFrozen) {
            p.sendChatMessage("/gamerule doDaylightCycle " + origDaylight);
        }
        daylightFrozen = timeChanged = weatherChanged = false;
    }

    private String worldKey() {
        String key = "default";
        if (mc.getIntegratedServer() != null) key = mc.getIntegratedServer().getFolderName();
        else if (mc.getCurrentServerData() != null) key = mc.getCurrentServerData().serverIP;
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String newTag() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    }

    /** All clips of one video are recorded: merge it in the background, then loop or stop. */
    private void finishAll() {
        startMerge();
        clipIndex = 0;
        batchTag = newTag();
        if (CinematicConfig.clipLoop && !stopAfterVideo) {
            planClip();           // keep going with the next video
        } else {
            restorePlayer();
        }
    }

    private void startMerge() {
        chat("Clips recorded, merging video...");
        final List<ClipRecorder> recs = new ArrayList<>(recorders);
        recorders.clear();
        new Thread(() -> {
            for (ClipRecorder r : recs) r.awaitDone();
            List<File> files = new ArrayList<>();
            for (ClipRecorder r : recs) files.add(r.file());

            File music = MusicPicker.pick(mc.mcDataDir);
            File dir = files.get(0).getParentFile();
            File out = new File(dir, "cinematic_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".mp4");

            boolean ok = VideoMerger.merge(files, out, music, CinematicConfig.musicVolume,
                    files.size() * CinematicConfig.clipSeconds);
            if (ok && !CinematicConfig.keepClips) for (File f : files) f.delete();

            if (music == null) {
                try (java.io.PrintWriter pw = new java.io.PrintWriter(new File(dir, "music_debug.txt"), "UTF-8")) {
                    pw.println(MusicPicker.report);
                } catch (Exception ignored) { }
            }
            String msg = ok
                    ? "Done: " + out.getAbsolutePath() + (music == null ? " (music not found, see music_debug.txt)" : "")
                    : "Merge failed, see ffmpeg_log.txt in " + dir.getAbsolutePath();
            mc.addScheduledTask(() -> chat(msg));
        }, "cinematic-merge").start();
    }

    /** Emergency stop: unfinished clips of the current video are deleted, nothing half-done stays in the folder. */
    private void abort(String reason) {
        if (recorder != null) { recorder.finish(); recorder = null; }
        final List<ClipRecorder> recs = new ArrayList<>(recorders);
        recorders.clear();
        new Thread(() -> {
            for (ClipRecorder r : recs) {
                r.awaitDone();
                r.file().delete();
            }
        }, "cinematic-cleanup").start();
        restorePlayer();
        chat(reason);
    }

    private void chat(String s) {
        if (mc.player != null) mc.player.sendMessage(new TextComponentString("[Cinematic] " + s));
    }
}
