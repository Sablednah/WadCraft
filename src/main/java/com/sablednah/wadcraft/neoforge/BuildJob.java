package com.sablednah.wadcraft.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import com.sablednah.wadcraft.api.BuildResult;
import com.sablednah.wadcraft.build.VoxelModel;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places a {@link VoxelModel}, column by column, a budget of blocks per tick.
 *
 * <p>Blocks are set with client updates but <b>no neighbour updates</b>: sand
 * and gravel in the surrounding terrain stay put, and nothing in the build
 * reacts to the next block arriving. What was there before is recorded so
 * {@code /wadcraft undo} can put it back.</p>
 */
final class BuildJob implements PlacementJob {

    /** Beyond this many blocks the undo record is dropped rather than eating the heap. */
    private static final int UNDO_LIMIT = 8_000_000;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final VoxelModel model;
    private final BlockState[] states;
    private final BlockPos anchor;
    private final int turns;
    private final IntConsumer progress;
    private final CompletableFuture<BuildResult> future;
    private final long started = System.currentTimeMillis();

    private int column, run, y;
    private boolean runStarted;
    private long placed, skipped;
    private int lastPercent;

    private LongArrayList undoPositions = new LongArrayList();
    private List<BlockState> undoStates = new ArrayList<>();

    BuildJob(ServerLevel level, VoxelModel model, BlockState[] states, BlockPos anchor, int turns,
            IntConsumer progress, CompletableFuture<BuildResult> future) {
        this.level = level;
        this.model = model;
        // Turn every block with the build, so a stair still faces up its step.
        Rotation rotation = switch (turns & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        this.states = new BlockState[states.length];
        for (int k = 0; k < states.length; k++) this.states[k] = states[k].rotate(rotation);
        this.anchor = anchor;
        this.turns = turns;
        this.progress = progress;
        this.future = future;
    }

    @Override
    public boolean tick(int budget) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int total = model.width() * model.depth();
        while (budget > 0 && column < total) {
            int[] runs = model.columns()[column];
            if (run * 3 >= runs.length) {
                column++;
                run = 0;
                runStarted = false;
                continue;
            }
            int lo = runs[run * 3], hi = runs[run * 3 + 1];
            if (!runStarted) {
                y = lo;
                runStarted = true;
            }
            BlockState state = states[runs[run * 3 + 2]];
            int i = column % model.width(), j = column / model.width();
            int lx = i - model.originI(), lz = j - model.originJ();
            int rx, rz;
            switch (turns & 3) {
                case 1 -> { rx = -lz; rz = lx; }
                case 2 -> { rx = -lx; rz = -lz; }
                case 3 -> { rx = lz; rz = -lx; }
                default -> { rx = lx; rz = lz; }
            }
            while (y <= hi && budget > 0) {
                pos.set(anchor.getX() + rx, anchor.getY() + (y - model.originY()), anchor.getZ() + rz);
                y++;
                budget--;
                if (level.isOutsideBuildHeight(pos.getY())) {
                    skipped++;
                    continue;
                }
                BlockState before = level.getBlockState(pos);
                if (before == state) continue;
                if (undoPositions != null) {
                    undoPositions.add(pos.asLong());
                    undoStates.add(before);
                    if (undoPositions.size() > UNDO_LIMIT) {
                        undoPositions = null;
                        undoStates = null;
                    }
                }
                level.setBlock(pos, state, FLAGS);
                placed++;
            }
            if (y > hi) {
                run++;
                runStarted = false;
            }
        }
        int percent = (int) (100L * column / Math.max(1, total));
        if (percent / 25 > lastPercent / 25 && percent < 100) progress.accept(percent);
        lastPercent = percent;
        if (column < total) return false;
        future.complete(new BuildResult(model.mapName(), placed, skipped,
                System.currentTimeMillis() - started, undoPositions != null));
        return true;
    }

    @Override
    public void cancel(String why) {
        future.completeExceptionally(new IllegalStateException(why));
    }

    /** What was there before, for undo; null if the build was too big to remember. */
    UndoJob undo() {
        return undoPositions == null ? null : new UndoJob(level, undoPositions, undoStates);
    }
}
