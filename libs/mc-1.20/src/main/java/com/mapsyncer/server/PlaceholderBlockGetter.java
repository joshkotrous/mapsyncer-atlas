package com.mapsyncer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class PlaceholderBlockGetter implements BlockGetter {

    public static final PlaceholderBlockGetter INSTANCE = new PlaceholderBlockGetter();

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final FluidState EMPTY = Fluids.EMPTY.defaultFluidState();

    private PlaceholderBlockGetter() {}

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return AIR;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return EMPTY;
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
