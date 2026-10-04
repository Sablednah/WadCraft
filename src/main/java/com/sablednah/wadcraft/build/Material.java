package com.sablednah.wadcraft.build;

/**
 * What a run of blocks is made of, before it is a Minecraft block: a Doom wall
 * texture, a floor or ceiling flat, air, or a light at a given level.
 *
 * <p>{@code SLAB} is a half-step floor in that flat; a non-zero level makes it a
 * stair facing that way (north is -z, east is +x, as in Minecraft, before the
 * build is turned). {@code HAZARD} is a damaging
 * floor: level 0 where it is safe to be a liquid, 1 where it needs a solid
 * stand-in because a liquid would spill.</p>
 */
public record Material(Kind kind, String name, int level) {

    public enum Kind { AIR, WALL, FLAT, LIGHT, SLAB, HAZARD }

    /** A wall whose texture could not be found: the block mapper's fallback. */
    public static final String UNKNOWN = "?";

    public static final int FACING_NONE = 0;
    public static final int FACING_NORTH = 1;
    public static final int FACING_SOUTH = 2;
    public static final int FACING_WEST = 3;
    public static final int FACING_EAST = 4;

    public static final Material AIR = new Material(Kind.AIR, "", 0);
}
