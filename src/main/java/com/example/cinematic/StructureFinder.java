package com.example.cinematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Works out where to put the camera around a structure found with /locate. */
public class StructureFinder {

    public static class Anchor {
        public boolean valid;
        public double cx, cy, cz, radius;
        public List<int[]> points = new ArrayList<>();
    }

    public static class Corridor {
        public boolean valid;
        public double x, y, z;
        public float yaw;
        public int freeLen;
    }

    private static boolean isStructureBlock(Block b) {
        return SpotFinder.isArtificial(b) || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE
                || b == Blocks.SNOW /* igloo */ || b == Blocks.PRISMARINE;
    }

    /** Village / Temple / Mansion / Monument: finds the middle and size of the built part. */
    public static Anchor findSurface(World w, int px, int pz, String type) {
        final int R = 56, STEP = 3;
        boolean monument = type.equals("Monument");
        List<int[]> pts = new ArrayList<>();
        for (int dx = -R; dx <= R; dx += STEP) {
            for (int dz = -R; dz <= R; dz += STEP) {
                int x = px + dx, z = pz + dz, y = -1;
                if (monument) {
                    for (int yy = 72; yy >= 25; yy--) {
                        if (w.getBlockState(new BlockPos(x, yy, z)).getBlock() == Blocks.PRISMARINE) { y = yy; break; }
                    }
                } else {
                    BlockPos top = w.getHeight(new BlockPos(x, 0, z));
                    if (isStructureBlock(w.getBlockState(top.down()).getBlock())) y = top.getY();
                }
                if (y >= 0) pts.add(new int[]{x, y, z});
            }
        }
        Anchor a = new Anchor();
        if (pts.size() < (monument ? 12 : 8)) return a;

        double sx = 0, sy = 0, sz = 0;
        for (int[] p : pts) { sx += p[0]; sy += p[1]; sz += p[2]; }
        a.cx = sx / pts.size();
        a.cy = sy / pts.size();
        a.cz = sz / pts.size();
        double maxD = 0;
        for (int[] p : pts) maxD = Math.max(maxD, Math.hypot(p[0] - a.cx, p[2] - a.cz));
        a.radius = Math.max(28, Math.min(60, maxD * 0.8 + 18));
        a.points = pts;
        a.valid = true;
        return a;
    }

    private static boolean passable(World w, int x, int y, int z) {
        IBlockState s = w.getBlockState(new BlockPos(x, y, z));
        return !s.getMaterial().blocksMovement();
    }

    /** A spot inside a building: roofed room with a free floor, plus the longest free view direction. */
    public static Corridor findInterior(World w, Anchor a, Random rnd) {
        Corridor best = new Corridor();
        int bestLen = 0;
        List<int[]> pts = new ArrayList<>(a.points);
        Collections.shuffle(pts, rnd);
        int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int tried = 0;
        for (int[] p : pts) {
            if (++tried > 150) break;
            for (int y = p[1] - 2; y >= p[1] - 14 && y > 5; y--) {
                if (!isStructureBlock(w.getBlockState(new BlockPos(p[0], y - 1, p[2])).getBlock())) continue;
                if (!passable(w, p[0], y, p[2]) || !passable(w, p[0], y + 1, p[2])) continue;
                boolean roofed = false;
                for (int k = 2; k <= 8; k++) {
                    if (!passable(w, p[0], y + k, p[2])) { roofed = true; break; }
                }
                if (!roofed) continue;
                for (int[] d : dirs) {
                    int len = 0;
                    for (int i = 1; i <= 10; i++) {
                        int x = p[0] + d[0] * i, z = p[2] + d[1] * i;
                        if (passable(w, x, y, z) && passable(w, x, y + 1, z)) len++;
                        else break;
                    }
                    if (len > bestLen) {
                        bestLen = len;
                        best.x = p[0] + 0.5;
                        best.y = y + 0.1;
                        best.z = p[2] + 0.5;
                        best.yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
                        best.freeLen = len;
                    }
                }
                break;   // one floor per column is enough
            }
            if (bestLen >= 6) break;
        }
        best.valid = bestLen >= 3;
        return best;
    }

    /** Mineshaft: looks for rails and picks a long straight free tunnel to look along. */
    public static Corridor findMineshaft(World w, int px, int pz, Random rnd) {
        final int R = 40;
        List<BlockPos> rails = new ArrayList<>();
        for (int x = px - R; x <= px + R; x++) {
            for (int z = pz - R; z <= pz + R; z++) {
                for (int y = 10; y <= 62; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (w.getBlockState(p).getBlock() instanceof BlockRailBase) rails.add(p);
                }
            }
        }
        Collections.shuffle(rails, rnd);

        Corridor best = new Corridor();
        int bestLen = 0;
        int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int tried = 0;
        for (BlockPos r : rails) {
            if (++tried > 300) break;
            if (!passable(w, r.getX(), r.getY() + 1, r.getZ()) || !passable(w, r.getX(), r.getY() + 2, r.getZ())) continue;
            for (int[] d : dirs) {
                int len = 0;
                for (int i = 1; i <= 16; i++) {
                    int x = r.getX() + d[0] * i, z = r.getZ() + d[1] * i;
                    if (passable(w, x, r.getY() + 1, z) && passable(w, x, r.getY() + 2, z)) len++;
                    else break;
                }
                if (len > bestLen) {
                    bestLen = len;
                    best.x = r.getX() + 0.5;
                    best.y = r.getY() + 0.3;
                    best.z = r.getZ() + 0.5;
                    best.yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
                    best.freeLen = len;
                }
            }
            if (bestLen >= 12) break;
        }
        best.valid = bestLen >= 8;
        return best;
    }
}
