package com.sablednah.wadcraft.neoforge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One placement at a time, a slice per server tick. Builds wait their turn
 * rather than run side by side, so the per-tick cost stays one budget however
 * many are asked for.
 */
public final class BuildQueue {

    /** Blocks per tick. Measured to keep a dedicated server near 20 TPS. */
    public static final int BLOCKS_PER_TICK = 20_000;

    private static final Deque<PlacementJob> QUEUE = new ArrayDeque<>();
    /** Each player's most recent finished build, for /wadcraft undo. Kept in memory only. */
    private static final Map<UUID, UndoJob> LAST_BUILD = new HashMap<>();

    static void add(PlacementJob job) {
        QUEUE.add(job);
    }

    static void rememberUndo(UUID who, UndoJob undo) {
        if (undo == null) LAST_BUILD.remove(who);
        else LAST_BUILD.put(who, undo);
    }

    static UndoJob takeUndo(UUID who) {
        return LAST_BUILD.remove(who);
    }

    static int waiting() {
        return QUEUE.size();
    }

    public static void tick() {
        PlacementJob job = QUEUE.peek();
        if (job != null && job.tick(BLOCKS_PER_TICK)) QUEUE.poll();
    }

    /** Stop everything. Returns how many jobs were stopped. */
    static int cancelAll(String why) {
        int n = QUEUE.size();
        for (PlacementJob job : QUEUE) job.cancel(why);
        QUEUE.clear();
        return n;
    }

    public static void clear() {
        cancelAll("the server is stopping");
        LAST_BUILD.clear();
    }

    private BuildQueue() {}
}
