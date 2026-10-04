package com.sablednah.wadcraft.neoforge;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Puts back what a build replaced, newest first. Block states only: a chest
 * that was in the way comes back empty, because a build does not keep block
 * entities.
 */
final class UndoJob implements PlacementJob {

    private final ServerLevel level;
    private final LongArrayList positions;
    private final List<BlockState> states;
    private int next;
    final CompletableFuture<Integer> done = new CompletableFuture<>();

    UndoJob(ServerLevel level, LongArrayList positions, List<BlockState> states) {
        this.level = level;
        this.positions = positions;
        this.states = states;
        this.next = positions.size() - 1;
    }

    @Override
    public boolean tick(int budget) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget-- > 0 && next >= 0) {
            pos.set(positions.getLong(next));
            level.setBlock(pos, states.get(next), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            next--;
        }
        if (next >= 0) return false;
        done.complete(positions.size());
        return true;
    }

    @Override
    public void cancel(String why) {
        done.completeExceptionally(new IllegalStateException(why));
    }
}
