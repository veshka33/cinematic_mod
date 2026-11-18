package com.example.cinematic;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class VideoMerger {

    public static boolean ffmpegOk(String ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            while (in.read() != -1) { }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean merge(List<File> clips, File out, File music, double volume, int totalSeconds) {
        try {
            File dir = out.getParentFile();
            File list = new File(dir, out.getName() + ".concat.txt");
            try (PrintWriter pw = new PrintWriter(list, "UTF-8")) {
                for (File c : clips) pw.println("file '" + c.getAbsolutePath().replace("\\", "/") + "'");
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(CinematicConfig.ffmpegPath);
            cmd.add("-y"); cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("warning");
            cmd.add("-f"); cmd.add("concat"); cmd.add("-safe"); cmd.add("0"); cmd.add("-i"); cmd.add(list.getAbsolutePath());

            if (music != null) {
                if (CinematicConfig.randomMusicOffset) {
                    cmd.add("-ss"); cmd.add(String.valueOf(new Random().nextInt(40)));
                }
                cmd.add("-stream_loop"); cmd.add("-1");
                cmd.add("-i"); cmd.add(music.getAbsolutePath());
                cmd.add("-map"); cmd.add("0:v"); cmd.add("-map"); cmd.add("1:a");
                cmd.add("-c:v"); cmd.add("copy");
                cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("192k");
                cmd.add("-af"); cmd.add(String.format(Locale.ROOT,
                        "volume=%.2f,afade=t=in:d=1,afade=t=out:st=%d:d=2", volume, Math.max(1, totalSeconds - 2)));
                cmd.add("-shortest");
            } else {
                cmd.add("-c"); cmd.add("copy");
            }
            cmd.add(out.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(dir, "ffmpeg_log.txt")));
            boolean ok = pb.start().waitFor() == 0;
            list.delete();
            return ok && out.exists();
        } catch (Exception e) {
            return false;
        }
    }
}
