package com.sablednah.wadcraft.neoforge;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import com.sablednah.wadcraft.api.BuildResult;
import com.sablednah.wadcraft.build.BuildOptions;
import com.sablednah.wadcraft.build.VoxelModel;
import com.sablednah.wadcraft.build.Voxelizer;
import com.sablednah.wadcraft.wad.DoomMap;
import com.sablednah.wadcraft.wad.TextureColours;
import com.sablednah.wadcraft.wad.WadFile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where a build actually starts: read and lay out the map off the server
 * thread, then hand the placement to the {@link BuildQueue} on it.
 */
public final class Builds {

    public static CompletableFuture<BuildResult> start(ServerLevel level, WadFile wad, String map,
            BlockPos anchor, int quarterTurns, BuildOptions options, TextureColours colours,
            UUID owner, IntConsumer progress) {
        MinecraftServer server = level.getServer();
        CompletableFuture<BuildResult> result = new CompletableFuture<>();
        record Prepared(VoxelModel model, TextureColours colours) {}

        CompletableFuture.supplyAsync(() -> {
            try {
                VoxelModel model = Voxelizer.build(DoomMap.read(wad, map), options);
                return new Prepared(model, colours != null ? colours : WadLibrary.colours(wad, map));
            } catch (java.io.IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.backgroundExecutor()).whenCompleteAsync((prepared, error) -> {
            if (error != null) {
                result.completeExceptionally(error instanceof java.util.concurrent.CompletionException c
                        && c.getCause() != null ? c.getCause() : error);
                return;
            }
            BlockState[] states = WadLibrary.palette().resolve(prepared.model().materials(), prepared.colours());
            CompletableFuture<BuildResult> placed = new CompletableFuture<>();
            BuildJob job = new BuildJob(level, prepared.model(), states, anchor, quarterTurns,
                    progress != null ? progress : p -> {}, placed);
            placed.whenComplete((done, failed) -> {
                if (failed != null) {
                    result.completeExceptionally(failed);
                } else {
                    if (owner != null) BuildQueue.rememberUndo(owner, job.undo());
                    result.complete(done);
                }
            });
            BuildQueue.add(job);
        }, server);
        return result;
    }

    private Builds() {}
}
