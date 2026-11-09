package com.mapsyncer.server;

import com.mapsyncer.config.CacheConfig;
import com.mapsyncer.mca.BlockPropertyLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralPlantBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DeadBushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BlockPropertyResolver {

    public static final BlockPropertyLookup INSTANCE = new BlockPropertyLookup() {
        @Override public int getFlags(String name) { return BlockPropertyResolver.getFlags(name); }
        @Override public boolean isWater(String name) { return BlockPropertyResolver.isWater(name); }
        @Override public boolean isTransparent(String name) { return BlockPropertyResolver.isTransparent(name); }
        @Override public boolean isInvisible(String name) { return BlockPropertyResolver.isInvisible(name); }
        @Override public boolean shouldOverlay(String name) { return BlockPropertyResolver.shouldOverlay(name); }
        @Override public boolean hasVanillaColor(String name) { return BlockPropertyResolver.hasVanillaColor(name); }
        @Override public boolean isGrassBlock(String name) { return BlockPropertyResolver.isGrassBlock(name); }
        @Override public boolean isGlowing(String name) { return BlockPropertyResolver.isGlowing(name); }
        @Override public boolean isTranslucentFluid(String name) { return BlockPropertyResolver.isTranslucentFluid(name); }
        @Override public boolean isWaterloggedSurface(String name, Map<String, String> props) { return BlockPropertyResolver.isWaterloggedSurface(name, props); }
        @Override public boolean isWaterInheriting(String name) { return BlockPropertyResolver.isWaterInheriting(name); }
        @Override public int getLightBlock(String name) { return BlockPropertyResolver.getLightBlock(name); }
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPropertyResolver.class);

    private static final BlockGetter PLACEHOLDER_BLOCK_GETTER = PlaceholderBlockGetter.INSTANCE;
    private static final BlockPos PLACEHOLDER_BLOCKPOS = BlockPos.ZERO;

    private static final ConcurrentHashMap<String, BlockProperties> propertiesCache = new ConcurrentHashMap<>();

    private static final int MAX_CACHE_SIZE = CacheConfig.MAX_BLOCK_PROPERTIES_CACHE;

    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    public record BlockProperties(
        boolean isAir,
        boolean isWater,
        boolean isLava,
        boolean isFluid,
        boolean isTransparent,
        boolean isInvisible,
        boolean isFlower,
        boolean isPlant,
        boolean isGrassBlock,
        boolean isGlowing,
        int lightBlock,
        int lightEmission,
        boolean canBeWaterlogged,
        boolean hasVanillaColor,
        boolean hasMapColor,
        boolean isAquaticPlant
    ) {

        public boolean isWaterloggedSurface(Map<String, String> properties) {
            if (properties == null) return false;
            return canBeWaterlogged &&
                   "true".equals(properties.get("waterlogged")) &&
                   !isWater && !isAir;
        }

        public boolean isTranslucentFluid() {
            return isWater;
        }

        public boolean shouldOverlay() {
            return isWater || isTransparent;
        }
    }

    public static BlockProperties getProperties(String blockName) {
        BlockProperties cached = propertiesCache.get(blockName);
        if (cached != null) {
            return cached;
        }
        BlockProperties resolved = resolveProperties(blockName);
        propertiesCache.put(blockName, resolved);
        trimCacheIfNeeded();
        return resolved;
    }

    private static void trimCacheIfNeeded() {
        int targetSize = (MAX_CACHE_SIZE * 3) / 4;
        int toRemove = propertiesCache.size() - targetSize;
        if (toRemove <= 0) {
            return;
        }
        var it = propertiesCache.keySet().iterator();
        while (toRemove > 0 && it.hasNext()) {
            it.next();
            it.remove();
            toRemove--;
        }
    }

    public static BlockProperties getProperties(BlockState state) {
        String blockName = getKey(state);
        return getProperties(blockName);
    }

    private static BlockProperties resolveProperties(String blockName) {
        try {
            ResourceLocation location = new ResourceLocation(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(location);

            if (blockOpt.isEmpty()) {
                LOGGER.debug("Block not found in registry: {}, using fallback", blockName);
                return getFallbackProperties(blockName);
            }

            Block block = blockOpt.get();

            BlockState defaultState = block.defaultBlockState();

            boolean isAir = defaultState.isAir() || block instanceof AirBlock;

            boolean isFluid = block instanceof LiquidBlock;
            FluidState fluidState = defaultState.getFluidState();
            Fluid fluid = fluidState.getType();
            boolean isWater = fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
            boolean isLava = fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;

            boolean isTransparent = checkTransparency(block, defaultState);

            boolean isInvisible = checkInvisibility(block, defaultState, true);

            boolean isFlower = checkIsFlower(block, defaultState);

            boolean isPlant = checkIsPlant(block, defaultState, isFlower);

            boolean isGrassBlock = block == Blocks.GRASS_BLOCK;

            int lightEmission = defaultState.getLightEmission();
            boolean isGlowing = lightEmission >= 15;

            int lightBlock = getLightBlock(defaultState);

            boolean canBeWaterlogged = checkCanBeWaterlogged(block, defaultState);

            boolean isAquaticPlant = checkIsAquaticPlant(block, defaultState);

            boolean hasMapColor = checkHasMapColor(defaultState, blockName);

            boolean hasVanillaColor = !isAir && !isInvisible && !buggedBlocks.containsKey(blockName) && hasMapColor;

            return new BlockProperties(
                isAir, isWater, isLava, isFluid,
                isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
                isGlowing, lightBlock, lightEmission, canBeWaterlogged,
                hasVanillaColor, hasMapColor, isAquaticPlant
            );

        } catch (RuntimeException e) {
            LOGGER.warn("Failed to resolve block properties for {}: {}", blockName, e.getMessage());
            return getFallbackProperties(blockName);
        }
    }

    private static boolean checkHasMapColor(BlockState state, String blockName) {

        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        if (state.getBlock() == Blocks.GRASS_BLOCK) {
            return true;
        }

        try {
            MapColor mapColor = state.getMapColor(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
            if (mapColor != null && mapColor.col != 0) {
                return true;
            }
        } catch (Throwable t) {

            buggedBlocks.put(blockName, true);
            LOGGER.debug("Broken vanilla map color definition found: {}", blockName);
        }
        return false;
    }

    private static int getLightBlock(BlockState state) {
        try {

            return state.getLightBlock(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
        } catch (RuntimeException e) {

            FluidState fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {

                if (fluidState.getType() == Fluids.WATER || fluidState.getType() == Fluids.FLOWING_WATER) {
                    return 1;
                }
                if (fluidState.getType() == Fluids.LAVA || fluidState.getType() == Fluids.FLOWING_LAVA) {
                    return 15;
                }
            }

            if (state.isAir()) {
                return 0;
            }

            if (state.is(BlockTags.LEAVES)) {
                return 1;
            }

            return 15;
        }
    }

    private static boolean checkTransparency(Block block, BlockState state) {

        if (block instanceof AirBlock || block instanceof HalfTransparentBlock) {
            return true;
        }

        if (block instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
            return true;
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            return true;
        }

        // (Forge: getRenderTypes.contains(translucent), Fabric: getRenderLayer==translucent)

        if (state.is(BlockTags.LEAVES)) {
            return false;
        }

        if (block == Blocks.SNOW) {
            return false;
        }

        int lightBlock = getLightBlock(state);
        if (lightBlock > 0 && lightBlock < 15) {
            return true;
        }

        return false;
    }

    private static boolean checkInvisibility(Block block, BlockState state, boolean flowers) {

        if (!(block instanceof LiquidBlock) &&
            state.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();

        if (block == Blocks.TORCH || blockId.contains("torch") || blockId.endsWith("_torch")) {
            return true;
        }

        if (block == Blocks.GRASS) {
            return true;
        }

        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE) {
            return true;
        }

        boolean isFlower = checkIsFlower(block, state);

        if (block instanceof DoublePlantBlock && !isFlower) {
            return true;
        }

        if (isFlower && !flowers) {
            return true;
        }

        String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (buggedBlocks.containsKey(blockName)) {
            return true;
        }

        return false;
    }

    private static boolean checkIsFlower(Block block, BlockState state) {

        if (state.is(BlockTags.FLOWERS)) {
            return true;
        }

        if (block instanceof FlowerBlock) {
            return true;
        }

        if (block instanceof TallFlowerBlock) {
            return true;
        }

        if (block instanceof MushroomBlock) {
            return true;
        }

        if (block instanceof WaterlilyBlock) {
            return true;
        }

        if (block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM) {
            return true;
        }

        if (block instanceof PitcherCropBlock) {
            return true;
        }

        if (block instanceof TorchflowerCropBlock) {
            return true;
        }

        return false;
    }

    private static boolean checkIsPlant(Block block, BlockState state, boolean isFlower) {

        if (isFlower) {
            return true;
        }

        if (block instanceof BushBlock) {
            return true;
        }

        if (block instanceof CropBlock) {
            return true;
        }

        if (block instanceof StemBlock) {
            return true;
        }

        if (block instanceof AttachedStemBlock) {
            return true;
        }

        if (block instanceof SaplingBlock) {
            return true;
        }

        if (block instanceof TallGrassBlock) {
            return true;
        }

        if (block instanceof DeadBushBlock) {
            return true;
        }

        if (block instanceof CactusBlock) {
            return true;
        }

        if (block instanceof SugarCaneBlock) {
            return true;
        }

        if (block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock) {
            return true;
        }

        if (block instanceof NetherWartBlock) {
            return true;
        }

        if (block instanceof GrowingPlantBlock || block instanceof GrowingPlantBodyBlock || block instanceof GrowingPlantHeadBlock) {
            return true;
        }

        if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
            return true;
        }

        if (block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock) {
            return true;
        }

        if (block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock) {
            return true;
        }

        if (block instanceof ChorusPlantBlock || block instanceof ChorusFlowerBlock) {
            return true;
        }

        if (block instanceof BaseCoralPlantBlock) {
            return true;
        }

        if (block instanceof BigDripleafBlock || block instanceof SmallDripleafBlock) {
            return true;
        }

        if (state.is(BlockTags.CROPS)) {
            return true;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("plant") || blockId.contains("crop") ||
            blockId.contains("sapling") || blockId.contains("seed") ||
            blockId.contains("vine") || blockId.contains("fern") ||
            blockId.contains("bush") || blockId.contains("grass") ||
            blockId.contains("cactus") || blockId.contains("reed") ||
            blockId.contains("stem") || blockId.contains("leaf") ||
            blockId.contains("mushroom") || blockId.contains("fungus")) {
            return true;
        }

        return false;
    }

    private static boolean checkCanBeWaterlogged(Block block, BlockState state) {

        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals("waterlogged")) {
                return true;
            }
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("fence_gate") || blockId.contains("stairs") ||
            blockId.contains("slab") || blockId.contains("wall") ||
            blockId.contains("door") || blockId.contains("trapdoor") ||
            blockId.contains("lantern") || blockId.contains("chain") ||
            blockId.contains("coral") || blockId.contains("grate") ||
            blockId.contains("sign") || blockId.contains("banner") ||
            blockId.contains("bed") || blockId.contains("scaffolding") ||
            blockId.contains("conduit") || blockId.contains("light") ||
            blockId.contains("sea_pickle") || blockId.contains("kelp")) {
            return true;
        }

        return false;
    }

    private static boolean checkIsAquaticPlant(Block block, BlockState state) {

        if (block == Blocks.SEAGRASS) {
            return true;
        }

        if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
            return true;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return blockId.equals("seagrass") || blockId.equals("tall_seagrass") ||
               blockId.equals("kelp") || blockId.equals("kelp_plant");
    }

    private static BlockProperties getFallbackProperties(String blockName) {
        String name = blockName.toLowerCase();

        boolean isAir = name.contains("air") || name.contains("void");
        boolean isWater = name.contains("water") && !name.contains("waterlogged");
        boolean isLava = name.contains("lava");
        boolean isFluid = isWater || isLava;

        boolean isTransparent = name.contains("glass") || name.contains("ice");

        boolean isInvisible = name.contains("torch") ||
                             (name.contains("grass") && !name.contains("grass_block") && !name.contains("tall"));

        boolean isFlower = name.contains("flower") || name.contains("rose") ||
                          name.contains("tulip") || name.contains("lily");

        boolean isPlant = isFlower || name.contains("plant") || name.contains("crop") ||
                         name.contains("sapling") || name.contains("seed") ||
                         name.contains("vine") || name.contains("fern") ||
                         name.contains("bush") || name.contains("grass") ||
                         name.contains("cactus") || name.contains("reed") ||
                         name.contains("stem") || name.contains("leaf") ||
                         name.contains("mushroom") || name.contains("fungus") ||
                         name.contains("wheat") || name.contains("carrot") ||
                         name.contains("potato") || name.contains("beetroot");

        boolean isGrassBlock = name.contains("grass_block");

        boolean isGlowing = name.contains("glow") || name.contains("lantern") ||
                           name.contains("lamp") || name.contains("torch") ||
                           name.contains("lava") || name.contains("fire");

        int lightBlock = isAir ? 0 : (isFluid || isTransparent ? 2 : 15);
        int lightEmission = isGlowing ? 15 : 0;

        boolean canBeWaterlogged = name.contains("fence") || name.contains("stairs") ||
                                  name.contains("slab") || name.contains("door") ||
                                  name.contains("trapdoor") || name.contains("wall") ||
                                  name.contains("lantern") || name.contains("coral");

        boolean isAquaticPlant = name.contains("seagrass") || name.contains("kelp");

        boolean hasVanillaColor = !isAir && !isInvisible;
        boolean hasMapColor = hasVanillaColor;

        return new BlockProperties(
            isAir, isWater, isLava, isFluid,
            isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
            isGlowing, lightBlock, lightEmission, canBeWaterlogged,
            hasVanillaColor, hasMapColor, isAquaticPlant
        );
    }

    public static String getKey(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public static String getKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static void clearCache() {
        propertiesCache.clear();
        buggedBlocks.clear();
    }

    public static int getCacheSize() {
        return propertiesCache.size();
    }

    public static int getBuggedBlocksCount() {
        return buggedBlocks.size();
    }

    public static boolean isAir(String blockName) {
        return getProperties(blockName).isAir();
    }

    public static boolean isWater(String blockName) {
        return getProperties(blockName).isWater();
    }

    public static boolean isLava(String blockName) {
        return getProperties(blockName).isLava();
    }

    public static boolean isFluid(String blockName) {
        return getProperties(blockName).isFluid();
    }

    public static boolean isTransparent(String blockName) {
        return getProperties(blockName).isTransparent();
    }

    public static boolean isInvisible(String blockName) {
        return getProperties(blockName).isInvisible();
    }

    public static boolean isFlower(String blockName) {
        return getProperties(blockName).isFlower();
    }

    public static boolean isPlant(String blockName) {
        return getProperties(blockName).isPlant();
    }

    public static boolean isGrassBlock(String blockName) {
        return getProperties(blockName).isGrassBlock();
    }

    public static boolean isGlowing(String blockName) {
        return getProperties(blockName).isGlowing();
    }

    public static int getLightBlock(String blockName) {
        return getProperties(blockName).lightBlock();
    }

    public static int getLightEmission(String blockName) {
        return getProperties(blockName).lightEmission();
    }

    public static boolean canBeWaterlogged(String blockName) {
        return getProperties(blockName).canBeWaterlogged();
    }

    public static boolean hasVanillaColor(String blockName) {
        return getProperties(blockName).hasVanillaColor();
    }

    public static boolean hasMapColor(String blockName) {
        return getProperties(blockName).hasMapColor();
    }

    public static boolean shouldOverlay(String blockName) {
        return getProperties(blockName).shouldOverlay();
    }

    public static boolean isTranslucentFluid(String blockName) {
        return getProperties(blockName).isTranslucentFluid();
    }

    public static boolean isWaterloggedSurface(String blockName, Map<String, String> properties) {
        return getProperties(blockName).isWaterloggedSurface(properties);
    }

    public static boolean isWaterInheriting(String blockName) {
        return getProperties(blockName).isAquaticPlant();
    }

    public static int getFlags(String blockName) {
        BlockProperties p = getProperties(blockName);
        int flags = 0;
        if (p.isWater()) flags |= BlockPropertyLookup.FLAG_WATER;
        if (p.isTransparent()) flags |= BlockPropertyLookup.FLAG_TRANSPARENT;
        if (p.isInvisible()) flags |= BlockPropertyLookup.FLAG_INVISIBLE;
        if (p.shouldOverlay()) flags |= BlockPropertyLookup.FLAG_SHOULD_OVERLAY;
        if (p.hasVanillaColor()) flags |= BlockPropertyLookup.FLAG_HAS_VANILLA_COLOR;
        if (p.isGlowing()) flags |= BlockPropertyLookup.FLAG_GLOWING;
        if (p.isTranslucentFluid()) flags |= BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID;
        if (p.isAquaticPlant()) flags |= BlockPropertyLookup.FLAG_WATER_INHERITING;
        return flags;
    }
}
