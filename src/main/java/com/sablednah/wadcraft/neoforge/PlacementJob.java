package com.sablednah.wadcraft.neoforge;

/** Something that sets blocks a slice at a time, so a big build never stalls a tick. */
interface PlacementJob {

    /** Set up to {@code budget} blocks. True when finished. */
    boolean tick(int budget);

    /** Stopped early: by /wadcraft cancel, or the server shutting down. */
    void cancel(String why);
}
