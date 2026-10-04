package com.sablednah.wadcraft.build;

import java.util.List;

/**
 * A map as block columns, ready to place.
 *
 * <p>Columns are indexed {@code j * width + i}: {@code i} runs west to east and
 * {@code j} north to south, matching Minecraft's x and z. Each column is a flat
 * array of {@code (yLow, yHigh, material)} triples, inclusive, with y in blocks
 * on the map's own vertical scale. The origin is player 1's start, standing on
 * its floor, so placing the origin at a player's feet puts them where Doom
 * would.</p>
 *
 * @param startAngle player 1's facing in Doom degrees (0 east, 90 north)
 * @param sectors    the Doom sector each column was built from, -1 outside the level
 * @param blocks     how many blocks placing this will set, air included
 */
public record VoxelModel(String mapName, int width, int depth, int originI, int originJ, int originY,
        int startAngle, List<Material> materials, int[][] columns, int[] sectors, long blocks) {

    public int[] column(int i, int j) {
        return columns[j * width + i];
    }

    /** Lowest and highest y (relative to the origin) any run reaches. */
    public int[] heightRange() {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] runs : columns) {
            for (int r = 0; r < runs.length; r += 3) {
                lo = Math.min(lo, runs[r] - originY);
                hi = Math.max(hi, runs[r + 1] - originY);
            }
        }
        return lo > hi ? new int[] {0, 0} : new int[] {lo, hi};
    }
}
