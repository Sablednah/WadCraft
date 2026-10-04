package com.sablednah.wadcraft.neoforge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sablednah.wadcraft.WadCraft;
import com.sablednah.wadcraft.build.Material;
import com.sablednah.wadcraft.wad.TextureColours;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Doom textures to Minecraft blocks.
 *
 * <p>Two steps. First the overrides in {@code config/wadcraft/blocks.json}, by
 * texture name with {@code *} as a wildcard: lava is magma (it hurts, as it did
 * in Doom), light panels glow. Then, for everything else, the block whose
 * average colour is nearest the texture's own. The averages come from the WAD
 * (see {@link TextureColours}), so a WAD this mod has never seen still builds in
 * the right colours without anybody writing a table for it.</p>
 */
public final class BlockPalette {

    /**
     * Opaque full blocks and their approximate average colours. Only blocks that
     * read as a wall or a floor: no ores, no logs with bark ends, nothing that
     * glows (that is for the overrides to choose deliberately).
     */
    private static final Object[][] CANDIDATES = {
            {"stone", 0x7D7D7D}, {"cobblestone", 0x7F7F7F}, {"smooth_stone", 0x9E9E9E},
            {"andesite", 0x888889}, {"polished_andesite", 0x848786}, {"diorite", 0xBCBCBC},
            {"polished_diorite", 0xC0C1C2}, {"granite", 0x956755}, {"polished_granite", 0x9A6A59},
            {"deepslate", 0x505052}, {"polished_deepslate", 0x484849}, {"deepslate_tiles", 0x363637},
            {"deepslate_bricks", 0x464646}, {"cobbled_deepslate", 0x4D4D50}, {"tuff", 0x6C6D66},
            {"tuff_bricks", 0x62675F}, {"calcite", 0xDFE0DC}, {"bricks", 0x966153},
            {"stone_bricks", 0x7A797A}, {"mossy_stone_bricks", 0x737969}, {"cracked_stone_bricks", 0x767576},
            {"nether_bricks", 0x2C151A}, {"red_nether_bricks", 0x450709}, {"blackstone", 0x2A2429},
            {"polished_blackstone", 0x353038}, {"polished_blackstone_bricks", 0x302B32},
            {"netherrack", 0x612626}, {"obsidian", 0x0F0A18}, {"quartz_block", 0xEBE5DE},
            {"sandstone", 0xD8CB9B}, {"cut_sandstone", 0xD9CE9F}, {"red_sandstone", 0xBA631D},
            {"mud_bricks", 0x89674F}, {"packed_mud", 0x8E6A4F}, {"terracotta", 0x985E43},
            {"iron_block", 0xDCDCDC}, {"gold_block", 0xF6D03D}, {"copper_block", 0xC06C50},
            {"exposed_copper", 0xA17D67}, {"weathered_copper", 0x6D916B}, {"oxidized_copper", 0x4F997E},
            {"prismarine", 0x639C97}, {"dark_prismarine", 0x335B4B}, {"end_stone_bricks", 0xDAE0A2},
            {"purpur_block", 0xA97DA9}, {"oak_planks", 0xA2824E}, {"spruce_planks", 0x725430},
            {"birch_planks", 0xC0AF79}, {"dark_oak_planks", 0x422B14}, {"jungle_planks", 0xA07351},
            {"mangrove_planks", 0x763631}, {"crimson_planks", 0x653147}, {"warped_planks", 0x2B6963},
            {"white_concrete", 0xCFD5D6}, {"light_gray_concrete", 0x7D7D73}, {"gray_concrete", 0x36393D},
            {"black_concrete", 0x080A0F}, {"brown_concrete", 0x603B1F}, {"red_concrete", 0x8E2020},
            {"orange_concrete", 0xE06100}, {"yellow_concrete", 0xF0AF15}, {"lime_concrete", 0x5EA818},
            {"green_concrete", 0x495B24}, {"cyan_concrete", 0x157788}, {"light_blue_concrete", 0x2389C6},
            {"blue_concrete", 0x2C2E8F}, {"purple_concrete", 0x641F9C}, {"magenta_concrete", 0xA9309F},
            {"pink_concrete", 0xD5658E}, {"white_terracotta", 0xD1B2A1}, {"orange_terracotta", 0xA15325},
            {"magenta_terracotta", 0x95586C}, {"light_blue_terracotta", 0x716C89},
            {"yellow_terracotta", 0xBA8523}, {"lime_terracotta", 0x677534}, {"pink_terracotta", 0xA14E4E},
            {"gray_terracotta", 0x392A23}, {"light_gray_terracotta", 0x876A61}, {"cyan_terracotta", 0x565B5B},
            {"purple_terracotta", 0x764656}, {"blue_terracotta", 0x4A3B5B}, {"brown_terracotta", 0x4D3323},
            {"green_terracotta", 0x4C532A}, {"red_terracotta", 0x8F3D2E}, {"black_terracotta", 0x251610},
    };

    /** Written to {@code config/wadcraft/blocks.json} the first time, then the file is the server owner's. */
    private static final Map<String, String> DEFAULT_OVERRIDES = new LinkedHashMap<>();

    static {
        DEFAULT_OVERRIDES.put("LAVA*", "minecraft:magma_block");
        DEFAULT_OVERRIDES.put("TLITE*", "minecraft:glowstone");
        DEFAULT_OVERRIDES.put("GRNLITE*", "minecraft:sea_lantern");
        DEFAULT_OVERRIDES.put("LITE*", "minecraft:glowstone");
        DEFAULT_OVERRIDES.put("CEIL1_2", "minecraft:sea_lantern");
        DEFAULT_OVERRIDES.put("CEIL1_3", "minecraft:glowstone");
        DEFAULT_OVERRIDES.put("?", "minecraft:stone");
    }

    private record Candidate(BlockState state, int rgb) {}

    private record Override(Pattern pattern, BlockState state) {}

    private final List<Candidate> candidates = new ArrayList<>();
    private final List<Override> overrides = new ArrayList<>();

    private BlockPalette() {}

    public static BlockPalette load(Path configDir) {
        BlockPalette palette = new BlockPalette();
        for (Object[] c : CANDIDATES) {
            block("minecraft:" + c[0]).ifPresent(b -> palette.candidates.add(new Candidate(b, (Integer) c[1])));
        }
        Path file = configDir.resolve("blocks.json");
        Map<String, String> entries = new LinkedHashMap<>(DEFAULT_OVERRIDES);
        try {
            if (Files.exists(file)) {
                entries.clear();
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject json = new Gson().fromJson(r, JsonObject.class);
                    JsonObject o = json != null && json.has("overrides") ? json.getAsJsonObject("overrides") : new JsonObject();
                    for (var e : o.entrySet()) entries.put(e.getKey(), e.getValue().getAsString());
                }
            } else {
                Files.createDirectories(configDir);
                JsonObject root = new JsonObject();
                root.addProperty("_comment", "Doom texture or flat name (upper case, * as a wildcard) -> block id. "
                        + "Anything not listed is matched to the block nearest its average colour.");
                JsonObject o = new JsonObject();
                DEFAULT_OVERRIDES.forEach(o::addProperty);
                root.add("overrides", o);
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            }
        } catch (IOException | RuntimeException e) {
            WadCraft.LOGGER.warn("Could not read {}; using the built-in overrides: {}", file, e.toString());
            entries = new LinkedHashMap<>(DEFAULT_OVERRIDES);
        }
        for (var e : entries.entrySet()) {
            Optional<BlockState> state = block(e.getValue());
            if (state.isEmpty()) {
                WadCraft.LOGGER.warn("blocks.json: no block called {} (for {})", e.getValue(), e.getKey());
                continue;
            }
            palette.overrides.add(new Override(glob(e.getKey()), state.get()));
        }
        return palette;
    }

    private static Pattern glob(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        StringBuilder re = new StringBuilder();
        for (String part : upper.split("\\*", -1)) {
            if (!re.isEmpty()) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }

    private static Optional<BlockState> block(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return Optional.empty();
        Block block = BuiltInRegistries.BLOCK.getValue(rl);
        return Optional.of(block.defaultBlockState());
    }

    /** Resolve every material a model uses, once, before placing. */
    public BlockState[] resolve(List<Material> materials, TextureColours colours) {
        BlockState[] out = new BlockState[materials.size()];
        for (int k = 0; k < out.length; k++) out[k] = resolve(materials.get(k), colours);
        return out;
    }

    private BlockState resolve(Material m, TextureColours colours) {
        return switch (m.kind()) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case LIGHT -> Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, Math.max(0, Math.min(15, m.level())));
            case WALL, FLAT -> {
                String name = m.name().toUpperCase(Locale.ROOT);
                for (Override o : overrides) {
                    if (o.pattern().matcher(name).matches()) yield o.state();
                }
                Optional<Integer> rgb = m.kind() == Material.Kind.FLAT ? colours.flat(name) : colours.wall(name);
                if (rgb.isEmpty()) rgb = m.kind() == Material.Kind.FLAT ? colours.wall(name) : colours.flat(name);
                yield rgb.map(this::nearest).orElse(Blocks.STONE.defaultBlockState());
            }
        };
    }

    /** "Redmean" distance: cheap, and much closer to how eyes rank colours than plain RGB. */
    private BlockState nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        BlockState best = Blocks.STONE.defaultBlockState();
        double bestD = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            int cr = (c.rgb() >> 16) & 0xFF, cg = (c.rgb() >> 8) & 0xFF, cb = c.rgb() & 0xFF;
            double rm = (r + cr) / 2.0;
            double dr = r - cr, dg = g - cg, db = b - cb;
            double d = (2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db;
            if (d < bestD) {
                bestD = d;
                best = c.state();
            }
        }
        return best;
    }
}
