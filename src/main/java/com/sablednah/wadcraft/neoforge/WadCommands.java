package com.sablednah.wadcraft.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.sablednah.wadcraft.api.BuildResult;
import com.sablednah.wadcraft.api.WadCraftApi;
import com.sablednah.wadcraft.build.BuildOptions;
import com.sablednah.wadcraft.build.VoxelModel;
import com.sablednah.wadcraft.wad.WadFile;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /wadcraft}: list, maps, info, build, undo, cancel. Operators only
 * (permission level 2): a build replaces whatever is in its way.
 */
public final class WadCommands {

    private static final int LOOK_RANGE = 160;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wadcraft")
                .requires(src -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src))
                .executes(WadCommands::help)
                .then(Commands.literal("list").executes(WadCommands::list))
                .then(Commands.literal("maps").then(wadArg().executes(WadCommands::maps)))
                .then(Commands.literal("info").then(wadArg().then(mapArg()
                        .executes(ctx -> info(ctx, BuildOptions.defaults().unitsPerBlock()))
                        .then(scaleArg().executes(ctx -> info(ctx, IntegerArgumentType.getInteger(ctx, "scale")))))))
                .then(Commands.literal("build").then(wadArg().then(mapArg()
                        .executes(ctx -> build(ctx, false, BuildOptions.defaults().unitsPerBlock()))
                        .then(Commands.literal("here")
                                .executes(ctx -> build(ctx, false, BuildOptions.defaults().unitsPerBlock()))
                                .then(scaleArg().executes(ctx -> build(ctx, false, IntegerArgumentType.getInteger(ctx, "scale")))))
                        .then(Commands.literal("look")
                                .executes(ctx -> build(ctx, true, BuildOptions.defaults().unitsPerBlock()))
                                .then(scaleArg().executes(ctx -> build(ctx, true, IntegerArgumentType.getInteger(ctx, "scale"))))))))
                .then(Commands.literal("undo").executes(WadCommands::undo))
                .then(Commands.literal("cancel").executes(WadCommands::cancel));
        dispatcher.register(root);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> wadArg() {
        return Commands.argument("wad", StringArgumentType.string()).suggests(WadCommands::suggestWads);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> mapArg() {
        return Commands.argument("map", StringArgumentType.word()).suggests(WadCommands::suggestMaps);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> scaleArg() {
        return Commands.argument("scale", IntegerArgumentType.integer(BuildOptions.MIN_SCALE, BuildOptions.MAX_SCALE));
    }

    private static CompletableFuture<Suggestions> suggestWads(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(WadLibrary.list().stream()
                .map(n -> n.matches("[0-9A-Za-z_.+-]+") ? n : '"' + n + '"'), b);
    }

    private static CompletableFuture<Suggestions> suggestMaps(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        try {
            WadFile wad = wad(ctx);
            return SharedSuggestionProvider.suggest(wad.maps().stream().map(WadFile.MapEntry::name), b);
        } catch (IOException | RuntimeException e) {
            return b.buildFuture();
        }
    }

    private static WadFile wad(CommandContext<CommandSourceStack> ctx) throws IOException {
        String name = StringArgumentType.getString(ctx, "wad");
        Path path = WadLibrary.resolve(name).orElseThrow(() -> new IOException(
                "No WAD called " + name + " in the wads folder. /wadcraft list shows what is there."));
        return WadLibrary.load(path);
    }

    // --- subcommands ---

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSystemMessage(heading("WadCraft").append(plain(
                " builds Doom maps as blocks.\n"
                + "/wadcraft list  - WAD files in the server's wads folder\n"
                + "/wadcraft maps <wad>  - the maps in one\n"
                + "/wadcraft info <wad> <map> [scale]  - size, before building\n"
                + "/wadcraft build <wad> <map> [here|look] [scale]  - you stand at the map's start, facing its way\n"
                + "/wadcraft undo  - put back what your last build replaced\n"
                + "/wadcraft cancel  - stop builds in progress\n"
                + "Scale is Doom units per block: 32 by default, smaller is bigger.")));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var names = WadLibrary.list();
        if (names.isEmpty()) {
            ctx.getSource().sendSystemMessage(plain("No WAD files yet. Put some in " + WadLibrary.folder()
                    + " - Freedoom (freedoom.github.io) is free to use."));
            return 0;
        }
        MutableComponent msg = heading(names.size() + " WAD file(s):");
        for (String n : names) {
            msg.append(Component.literal("\n  " + n).withStyle(style -> style.withColor(ChatFormatting.WHITE)
                    .withClickEvent(new ClickEvent.SuggestCommand("/wadcraft maps " + quoted(n)))));
        }
        ctx.getSource().sendSystemMessage(msg);
        return names.size();
    }

    private static int maps(CommandContext<CommandSourceStack> ctx) {
        try {
            WadFile wad = wad(ctx);
            var maps = wad.maps();
            if (maps.isEmpty()) {
                ctx.getSource().sendSystemMessage(plain(wad.label() + " has no maps in it (it may be a texture or resource WAD)."));
                return 0;
            }
            MutableComponent msg = heading(wad.label() + " (" + wad.kind() + "), " + maps.size() + " map(s):");
            msg.append(plain("\n "));
            for (WadFile.MapEntry m : maps) {
                boolean ok = m.format() != WadFile.MapFormat.UDMF;
                msg.append(Component.literal(" " + m.name()).withStyle(style -> style
                        .withColor(ok ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY)
                        .withClickEvent(new ClickEvent.SuggestCommand(
                                "/wadcraft build " + quoted(wad.label()) + " " + m.name()))));
            }
            if (maps.stream().anyMatch(m -> m.format() == WadFile.MapFormat.UDMF)) {
                msg.append(plain("\nGrey maps are UDMF, which WadCraft cannot build yet."));
            }
            msg.append(plain("\nClick a map to build it where you stand."));
            ctx.getSource().sendSystemMessage(msg);
            return maps.size();
        } catch (IOException e) {
            return fail(ctx, e.getMessage());
        }
    }

    private static int info(CommandContext<CommandSourceStack> ctx, int scale) {
        try {
            WadFile wad = wad(ctx);
            String map = StringArgumentType.getString(ctx, "map").toUpperCase(Locale.ROOT);
            VoxelModel model = WadCraftApi.model(wad, map, BuildOptions.defaults().withScale(scale));
            int[] h = model.heightRange();
            ctx.getSource().sendSystemMessage(heading(map + " at " + scale + " units a block: ").append(plain(
                    model.width() + " x " + model.depth() + " blocks across, " + (h[1] - h[0] + 1) + " high ("
                    + h[0] + " to +" + h[1] + " from where you stand), " + String.format("%,d", model.blocks())
                    + " blocks to place.")));
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
    }

    private static int build(CommandContext<CommandSourceStack> ctx, boolean look, int scale) {
        CommandSourceStack src = ctx.getSource();
        WadFile wad;
        String map = StringArgumentType.getString(ctx, "map").toUpperCase(Locale.ROOT);
        try {
            wad = wad(ctx);
            if (wad.map(map).isEmpty()) {
                return fail(ctx, wad.label() + " has no map called " + map + ". /wadcraft maps " + quoted(wad.label())
                        + " lists them.");
            }
        } catch (IOException e) {
            return fail(ctx, e.getMessage());
        }

        ServerPlayer player = src.getPlayer();
        BlockPos anchor;
        float yaw;
        if (look) {
            if (player == null) return fail(ctx, "Only a player can build where they are looking. Use 'here'.");
            HitResult hit = player.pick(LOOK_RANGE, 1.0F, false);
            if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
                return fail(ctx, "Nothing in sight to build on. Look at a block within " + LOOK_RANGE
                        + " blocks, or use 'here'.");
            }
            anchor = bhr.getBlockPos().relative(bhr.getDirection());
            yaw = player.getYRot();
        } else {
            anchor = BlockPos.containing(src.getPosition());
            yaw = src.getRotation().y;
        }

        int turns;
        try {
            turns = WadCraftApi.turnsToFace(wad, map, yaw);
        } catch (IOException e) {
            return fail(ctx, e.getMessage());
        }
        BuildOptions options = BuildOptions.defaults().withScale(scale);
        int waiting = BuildQueue.waiting();
        src.sendSystemMessage(plain("Building " + map + " from " + wad.label() + " at " + scale + " units a block"
                + (waiting > 0 ? ", after " + waiting + " build(s) already queued" : "") + "..."));

        UUID owner = player != null ? player.getUUID() : null;
        WadCraftApi.build(src.getLevel(), wad, map, anchor, turns, options, owner,
                percent -> {
                    if (player != null) player.sendOverlayMessage(plain(map + ": " + percent + "%"));
                }).whenComplete((result, error) -> {
                    if (error != null) {
                        src.sendFailure(Component.literal("Could not build " + map + ": " + error.getMessage()));
                        return;
                    }
                    report(src, result);
                });
        return 1;
    }

    private static void report(CommandSourceStack src, BuildResult r) {
        MutableComponent msg = plain(String.format("Built %s in %.1fs: %,d blocks changed.", r.mapName(),
                r.millis() / 1000.0, r.placed()));
        if (r.undoable()) {
            msg.append(plain(" ")).append(Component.literal("/wadcraft undo").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.SuggestCommand("/wadcraft undo"))))
                    .append(plain(" puts back what was there."));
        } else {
            msg.append(plain(" It was too big to remember, so it cannot be undone."));
        }
        src.sendSystemMessage(msg);
        if (r.skipped() > 0) {
            src.sendSystemMessage(Component.literal(String.format(
                    "%,d blocks fell outside the world's height and were left out. Build higher or lower to get the whole map.",
                    r.skipped())).withStyle(ChatFormatting.GOLD));
        }
    }

    private static int undo(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx, "Undo is per player: run it as the player who built.");
        UndoJob job = BuildQueue.takeUndo(player.getUUID());
        if (job == null) return fail(ctx, "Nothing to undo: you have no finished build since the server started.");
        ctx.getSource().sendSystemMessage(plain("Putting back what your last build replaced..."));
        job.done.whenComplete((n, error) -> {
            if (error == null) ctx.getSource().sendSystemMessage(plain(String.format("Undone: %,d blocks restored.", n)));
        });
        BuildQueue.add(job);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        int n = BuildQueue.cancelAll("cancelled by " + ctx.getSource().getTextName());
        ctx.getSource().sendSystemMessage(plain(n == 0 ? "Nothing is building."
                : "Stopped " + n + " job(s). What was already placed stays; /wadcraft undo cannot reach a cancelled build."));
        return n;
    }

    // --- text ---

    private static String quoted(String name) {
        return name.matches("[0-9A-Za-z_.+-]+") ? name : '"' + name + '"';
    }

    private static MutableComponent heading(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent plain(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private WadCommands() {}
}
