package com.sablednah.wadcraft.build;

/**
 * What a run of blocks is made of, before it is a Minecraft block: a Doom wall
 * texture, a floor or ceiling flat, air, or a light at a given level.
 */
public record Material(Kind kind, String name, int level) {

    public enum Kind { AIR, WALL, FLAT, LIGHT }

    /** A wall whose texture could not be found: the block mapper's fallback. */
    public static final String UNKNOWN = "?";

    public static final Material AIR = new Material(Kind.AIR, "", 0);
}
