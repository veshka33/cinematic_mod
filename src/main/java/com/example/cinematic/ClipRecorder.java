package com.example.cinematic;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Streams raw frames from the game framebuffer into an ffmpeg process (one clip). */
public class ClipRecorder {
    private final File file;
    private final Process proc;
    private final OutputStream out;
    private final int w, h, fps;
    private final ByteBuffer buf;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> free = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closing;
    private long lastCapture = 0;

    public ClipRecorder(String ffmpeg, File file, int w, int h, int fps) throws IOException {
        this.file = file; this.w = w; this.h = h; this.fps = fps;
        List<String> cmd = Arrays.asList(ffmpeg, "-y", "-hide_banner", "-loglevel", "warning",
                "-use_wallclock_as_timestamps", "1",
                "-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-i", "-",
                "-vf", filterChain(fps),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                file.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(file.getParentFile(), "ffmpeg_log.txt")));
        proc = pb.start();
        out = proc.getOutputStream();
        buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < 6; i++) free.add(new byte[w * h * 4]);

        writer = new Thread(() -> {
            try {
                while (true) {
                    byte[] a = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (a == null) { if (closing) break; continue; }
                    out.write(a);
                    free.add(a);
                }
                out.close();
                proc.waitFor();
            } catch (Exception ignored) {
                proc.destroy();
            }
        }, "cinematic-writer");
        writer.start();
    }

    private static String filterChain(int fps) {
        String fmt = CinematicConfig.outputFormat.trim().toUpperCase(java.util.Locale.ROOT);
        int vw = CinematicConfig.shortsWidth / 2 * 2;
        int vh = (int) Math.round(vw * 16 / 9.0 / 2) * 2;

        StringBuilder sb = new StringBuilder("vflip,setpts=PTS-STARTPTS,fps=" + fps);
        if (fmt.equals("VERTICAL_BARS")) {
            double a = parseAspect(CinematicConfig.barsAspect);
            sb.append(String.format(java.util.Locale.ROOT,
                    ",crop='trunc(min(iw,ih*%.4f)/2)*2':'trunc(min(ih,iw/%.4f)/2)*2',scale=%d:-2,pad=%d:%d:0:(oh-ih)/2:black",
                    a, a, vw, vw, vh));
        } else if (fmt.equals("VERTICAL")) {
            sb.append(",crop=trunc(ih*9/16/2)*2:ih,scale=" + vw + ":" + vh);
        } else if (fmt.equals("VERTICAL_BLUR")) {
            sb.append(String.format(java.util.Locale.ROOT,
                    ",split[a][b];[a]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=25:5[bg];"
                            + "[b]scale=%d:-2[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2", vw, vh, vw, vh, vw));
        } else {
            sb.append(",scale=trunc(iw/2)*2:trunc(ih/2)*2");
        }

        double f = CinematicConfig.fadeSeconds;
        if (f > 0) {
            double outStart = Math.max(0, CinematicConfig.clipSeconds - f);
            sb.append(String.format(java.util.Locale.ROOT,
                    ",fade=t=in:st=0:d=%.2f,fade=t=out:st=%.2f:d=%.2f", f, outStart, f));
        }
        return sb.toString();
    }

    private static double parseAspect(String s) {
        try {
            String[] p = s.trim().split("[:/xX]");
            double a = Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            if (a >= 0.3 && a <= 3) return a;
        } catch (Exception ignored) { }
        return 1.0;
    }

    public File file() { return file; }

    /** Call at the end of a render tick (on the render thread). */
    public void addFrame(Framebuffer fb) {
        if (fb.framebufferTextureWidth != w || fb.framebufferTextureHeight != h) return; // window resized
        long now = System.nanoTime();
        if (now - lastCapture < (long) (1_000_000_000L / fps * 0.9)) return;
        byte[] a = free.poll();
        if (a == null) return; // encoder is behind: drop the frame
        lastCapture = now;
        buf.clear();
        GlStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        fb.bindFramebufferTexture();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        fb.unbindFramebufferTexture();
        buf.get(a);
        queue.add(a);
    }

    public void finish() { closing = true; }

    public void awaitDone() {
        try { writer.join(); } catch (InterruptedException ignored) { }
    }
}
