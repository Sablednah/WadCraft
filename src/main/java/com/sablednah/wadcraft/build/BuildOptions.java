package com.sablednah.wadcraft.build;

/**
 * How to turn a map into blocks.
 *
 * @param unitsPerBlock Doom units to one block. 32 makes the Doom player (56
 *                      units) about the height of a Minecraft one.
 * @param openDoors     build doors open, so the level can be walked
 * @param lights        place invisible light blocks at each sector's light level
 * @param halfSteps     floors in half blocks: a half step is a slab, so stairs walk
 * @param hazards       damaging floors (nukage, slime) become lava where it cannot spill
 */
public record BuildOptions(int unitsPerBlock, boolean openDoors, boolean lights, boolean halfSteps,
        boolean hazards) {

    public static final int MIN_SCALE = 8;
    public static final int MAX_SCALE = 128;

    public static BuildOptions defaults() {
        return new BuildOptions(32, true, true, true, true);
    }

    public BuildOptions {
        if (unitsPerBlock < MIN_SCALE || unitsPerBlock > MAX_SCALE) {
            throw new IllegalArgumentException("unitsPerBlock must be " + MIN_SCALE + " to " + MAX_SCALE);
        }
    }

    public BuildOptions withScale(int units) {
        return new BuildOptions(units, openDoors, lights, halfSteps, hazards);
    }
}
