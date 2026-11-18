package com.example.cinematic;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Remembers where clips were already filmed (one file per world). */
public class SpotMemory {
    private final List<double[]> spots = new ArrayList<>();
    private File file;

    public void load(File dir, String worldKey) {
        spots.clear();
        file = new File(dir, "used_spots_" + worldKey + ".txt");
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 2) spots.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            }
        } catch (Exception ignored) { }
    }

    public boolean isNear(double x, double z) {
        double d = CinematicConfig.minSpotDistance;
        for (double[] s : spots) {
            double dx = s[0] - x, dz = s[1] - z;
            if (dx * dx + dz * dz < d * d) return true;
        }
        return false;
    }

    public void add(double x, double z) {
        spots.add(new double[]{x, z});
        if (file == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
            pw.println((long) x + " " + (long) z);
        } catch (Exception ignored) { }
    }
}
