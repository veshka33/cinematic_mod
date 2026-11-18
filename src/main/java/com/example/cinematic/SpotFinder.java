package com.example.cinematic;

import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Rates how "pretty" the terrain around a point is and picks the best viewing direction. */
public class SpotFinder {
    public static class Spot {
        public double score;
        public float yaw;
        /** true = the spot breaks a hard rule (biome / too much water / buildings): never film here. */
        public boolean rejected;
    }

    private static Spot reject() {
        Spot s = new Spot();
        s.score = -100;
        s.rejected = true;
        return s;
    }

    public static Spot evaluate(World w, int cx, int cz) {
        final int R = 48, STEP = 3, N = 2 * R / STEP + 1;

        // biome rules (checked at the centre of the spot)
        Biome cb = w.getBiome(new BlockPos(cx, 64, cz));
        String bname = cb.getRegistryName() == null ? "" : cb.getRegistryName().getResourcePath().toLowerCase(Locale.ROOT);
        if (!biomeAllowed(bname)) return reject();

        double[] sector = new double[8];
        int[][] hgt = new int[N][N];
        boolean[][] caveBelow = new boolean[N][N];
        int total = 0, water = 0, trees = 0, snow = 0, artificial = 0, minY = 999, maxY = -999;
        int lava = 0, obsidian = 0, waterfalls = 0;
        int ringTotal = 0, ringWater = 0, innerTotal = 0, innerWater = 0;
        Set<Biome> biomes = new HashSet<>();
        int centerY = w.getHeight(new BlockPos(cx, 0, cz)).getY();

        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                int dx = -R + i * STEP, dz = -R + j * STEP;
                hgt[i][j] = -1;
                BlockPos top = w.getHeight(new BlockPos(cx + dx, 0, cz + dz));
                if (!w.isBlockLoaded(top)) continue;
                IBlockState st = w.getBlockState(top.down());
                Block blk = st.getBlock();
                Material mat = st.getMaterial();
                int y = top.getY();
                hgt[i][j] = y;
                total++;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                biomes.add(w.getBiome(top));
                if (isArtificial(blk)) artificial++;

                double dist = Math.sqrt(dx * dx + dz * dz);
                boolean isWater = mat == Material.WATER;
                if (dist >= 32) { ringTotal++; if (isWater) ringWater++; }
                if (dist < 24) { innerTotal++; if (isWater) innerWater++; }

                double v = 0;
                if (isWater) {
                    water++; v += 1.0;
                    if (blk == Blocks.FLOWING_WATER) {            // falling water column = waterfall
                        int len = 0;
                        BlockPos q = top.down();
                        while (len < 12 && w.getBlockState(q).getBlock() == Blocks.FLOWING_WATER) { len++; q = q.down(); }
                        if (len >= 4) { waterfalls++; v += 2.0; }
                    }
                } else {
                    if (mat == Material.LEAVES || mat == Material.WOOD) { trees++; v += 1.2; }
                    else if (mat == Material.SNOW || mat == Material.CRAFTED_SNOW
                            || mat == Material.ICE || mat == Material.PACKED_ICE) { snow++; v += 1.0; }
                    else if (mat == Material.LAVA) { lava++; v += 2.0; }
                    else if (blk == Blocks.OBSIDIAN) { obsidian++; v += 2.0; }
                    for (int k = 3; k <= 8; k++) {                 // air pocket under the surface = cave
                        if (w.isAirBlock(top.down(k))) { caveBelow[i][j] = true; break; }
                    }
                }
                v += Math.min(2.0, Math.abs(y - centerY) / 8.0);

                if (dist >= 12) {
                    int idx = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 4)) % 8;
                    sector[idx] += v;
                }
            }
        }

        if (total < 50) { Spot s = new Spot(); s.score = -100; return s; } // chunks not ready: just a bad try

        if (CinematicConfig.avoidStructures && artificial >= 3) return reject();
        double waterR = water / (double) total, treeR = trees / (double) total, snowR = snow / (double) total;

        // island: land in the middle, open water all around -> never rejected for "too much water"
        boolean island = ringTotal > 10 && innerTotal > 5
                && ringWater / (double) ringTotal >= 0.7 && innerWater / (double) innerTotal <= 0.4;
        if (!island && waterR > CinematicConfig.maxWaterRatio) return reject();

        Spot s = new Spot();
        s.score = Math.min(waterR, 0.3) * 10          // up to 3
                + Math.min(treeR, 0.4) * 7.5          // up to 3
                + Math.min(snowR, 0.5) * 4            // up to 2
                + Math.min((maxY - minY) / 40.0, 1.0) * 3  // relief, up to 3
                + Math.min(biomes.size(), 4) * 0.5;   // biome variety, up to 2

        // ---- unusual features
        double bonus = 0;
        if (waterfalls >= 1) bonus += Math.min(3.0, 1.5 + waterfalls * 0.25);          // waterfalls
        if ((lava >= 2 && water >= 2) || obsidian >= 1) bonus += 2.0;                  // lava next to water
        else if (lava >= 2) bonus += 0.8;
        if (snowR > 0.2 && (maxY - minY) >= 25) bonus += 2.0;                          // snowy mountains
        if (maxY >= 110 && (maxY - minY) >= 30) bonus += 1.0;                          // high peaks
        if (island) bonus += 2.5;                                                      // island
        int pits = 0;                                                                  // cave mouths / ravines
        for (int i = 1; i < N - 1; i++) {
            for (int j = 1; j < N - 1; j++) {
                int h = hgt[i][j];
                if (h < 0 || !caveBelow[i][j]) continue;
                if (hgt[i - 1][j] >= h + 3 && hgt[i + 1][j] >= h + 3 && hgt[i][j - 1] >= h + 3 && hgt[i][j + 1] >= h + 3) pits++;
            }
        }
        if (pits >= 1) bonus += pits >= 3 ? 2.0 : 1.5;
        s.score += bonus;

        int best = 0;
        for (int k = 1; k < 8; k++) if (sector[k] > sector[best]) best = k;
        double theta = -Math.PI + (best + 0.5) * Math.PI / 4;
        double dirX = Math.cos(theta), dirZ = Math.sin(theta);
        s.yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ)); // Minecraft yaw
        return s;
    }

    private static boolean biomeAllowed(String name) {
        for (String b : CinematicConfig.biomeBlacklist) {
            String k = b.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && name.contains(k)) return false;
        }
        boolean hasList = false;
        for (String b : CinematicConfig.biomeWhitelist) {
            String k = b.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) continue;
            hasList = true;
            if (name.contains(k)) return true;
        }
        return !hasList;
    }

    /** Blocks that do not occur naturally on the surface: villages, houses, paths, farms. */
    public static boolean isArtificial(Block b) {
        return b instanceof BlockStairs || b instanceof BlockSlab || b instanceof BlockFence
                || b instanceof BlockFenceGate || b instanceof BlockGlass || b instanceof BlockPane
                || b instanceof BlockFarmland || b instanceof BlockDoor || b instanceof BlockRailBase
                || b == Blocks.PLANKS || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE
                || b == Blocks.BRICK_BLOCK || b == Blocks.GRASS_PATH || b == Blocks.HAY_BLOCK
                || b == Blocks.STONEBRICK;
    }
}
