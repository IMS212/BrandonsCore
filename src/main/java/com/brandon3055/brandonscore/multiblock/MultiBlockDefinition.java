package com.brandon3055.brandonscore.multiblock;

import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Transformation;
import codechicken.lib.vec.Vector3;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by brandon3055 on 26/06/2022
 */
public class MultiBlockDefinition {
    private final ResourceLocation id;
    // Holding onto this, so I can yeet it at the client because I'm to lazy to write dedicated network serialization when I already have a convenient json and a way to read it.
    private final JsonElement json;

    /**
     * The structure origin offset.
     * This shouldn't be needed outside this class because this offset is already applied when the structure is loaded.
     * Meaning the block at 0, 0, 0 within the structure is the origin.
     * */
    private BlockPos origin = BlockPos.ZERO;
    //Position the position of each block relative to origin
    private Map<BlockPos, MultiBlockPart> blockMap = new HashMap<>();

    public MultiBlockDefinition(ResourceLocation id, JsonElement json) {
        this.id = id;
        this.json = json;
        loadFromJson();
    }

    public ResourceLocation getId() {
        return id;
    }

    public JsonElement getJson() {
        return json;
    }

    public Map<BlockPos, MultiBlockPart> getBlocks() {
        return ImmutableMap.copyOf(blockMap);
    }

    /**
     * Applies a rotation to the multi block structure. This rotation is applied relative to the structure's origin.
     * For obvious reasons the rotation angle must be a multiple of 90 degrees.
     *
     * @param rotation Rotation to apply to the structure.
     * @return The structure blocks with the applied rotation.
     */
    public Map<BlockPos, MultiBlockPart> getBlocksWithRotation(Rotation rotation) {
        Map<BlockPos, MultiBlockPart> transformed = new HashMap<>();
        Transformation transform = rotation.at(Vector3.CENTER).inverse();
        blockMap.forEach((pos, part) -> {
            Vector3 vec = Vector3.fromBlockPosCenter(pos);
            vec.apply(transform);
            transformed.put(vec.pos(), part);
        });
        return ImmutableMap.copyOf(transformed);
    }

    private void loadFromJson() {
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("origin")) {
            JsonObject originObj = obj.getAsJsonObject("origin");
            this.origin = new BlockPos(originObj.get("x").getAsInt(), originObj.get("y").getAsInt(), originObj.get("z").getAsInt());
        }

        Map<String, MultiBlockPart> keyMap = new HashMap<>();
        JsonObject keysObj = obj.getAsJsonObject("keys");
        for (Map.Entry<String, JsonElement> entry : keysObj.entrySet()) {
            String key = entry.getKey();
            if (keyMap.containsKey(key)) {
                throw new IllegalStateException("Duplicate key detected!, " + id);
            }

            JsonObject keyVal = entry.getValue().getAsJsonObject();
            if (keyVal.has("tag")) {
                ResourceLocation resourcelocation = new ResourceLocation(keyVal.get("tag").getAsString());
                TagKey<Block> tagkey = TagKey.create(Registry.BLOCK_REGISTRY, resourcelocation);
                keyMap.put(key, new TagPart(tagkey));
            } else if (keyVal.has("block")) {
                ResourceLocation resourcelocation = new ResourceLocation(keyVal.get("block").getAsString());
                if (Blocks.AIR.getRegistryName().equals(resourcelocation)) {
                    keyMap.put(key, new EmptyPart());
                } else {
                    keyMap.put(key, new BlockPart(resourcelocation));
                }
            } else {
                throw new IllegalArgumentException("Invalid block key detected!, " + keyVal + ", " + id);
            }
        }

        JsonArray structure = obj.getAsJsonArray("structure");
        int layer = 0; //Y Pos
        for (JsonElement layerElement : structure) {
            JsonArray layerArray = layerElement.getAsJsonArray();
            int row = 0; //Z Pos
            for (JsonElement rowElement : layerArray) {
                String rowString = rowElement.getAsString();
                for (int i = 0; i < rowString.length(); i++) {
                    String key = String.valueOf(rowString.charAt(i));
                    BlockPos pos = new BlockPos(i, layer, row).subtract(origin);
                    if (blockMap.containsKey(pos)) {
                        throw new IllegalStateException("What?");
                    }
                    if (!key.equals(" ")){
                        if (!keyMap.containsKey(key)) {
                            throw new IllegalArgumentException("Undefined key in multiblock definition: " + id + ", Key: " + key);
                        }
                        blockMap.put(pos, keyMap.get(key));
                    }
                }
                row++;
            }
            layer++;
        }
    }

    private static class BlockPart implements MultiBlockPart {
        private final Block block;

        public BlockPart(ResourceLocation id) {
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                throw new IllegalStateException("Specified block could not be found: " + id);
            }
            this.block = ForgeRegistries.BLOCKS.getValue(id);
        }

        @Override
        public boolean isMatch(Level level, BlockPos pos) {
            return level.getBlockState(pos).is(block);
        }

        @Override
        public Collection<Block> validBlocks() {
            return Collections.singleton(block);
        }
    }

    private static class TagPart implements MultiBlockPart {
        private TagKey<Block> tag;
        private List<Block> blockCache;

        public TagPart(TagKey<Block> tag) {
            this.tag = tag;
        }

        @Override
        public boolean isMatch(Level level, BlockPos pos) {
            return level.getBlockState(pos).is(tag);
        }

        @Override
        public Collection<Block> validBlocks() {
            if (blockCache == null) {
                blockCache = ForgeRegistries.BLOCKS.getValues()
                        .stream()
                        .filter(block -> block.defaultBlockState().is(tag))
                        .collect(Collectors.toList());
            }
            return blockCache;
        }
    }

    private static class EmptyPart implements MultiBlockPart {
        @Override
        public boolean isMatch(Level level, BlockPos pos) {
            return level.isEmptyBlock(pos);
        }

        @Override
        public Collection<Block> validBlocks() {
            return Collections.singleton(Blocks.AIR);
        }
    }
}
