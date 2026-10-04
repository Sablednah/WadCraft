package com.sablednah.wadcraft.api;

/**
 * A finished build.
 *
 * @param placed   blocks actually changed (a block already right is not counted)
 * @param skipped  blocks that fell outside the world's build height
 * @param undoable whether the replaced blocks were recorded for undo
 */
public record BuildResult(String mapName, long placed, long skipped, long millis, boolean undoable) {}
