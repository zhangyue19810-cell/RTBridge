package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MixinLevelChunk — detect emissive blocks during chunk load.
 *
 * When a chunk is loaded, we scan its block states for emissive blocks
 * (getLuminance() > 0) and register them with the EmissiveCache.
 *
 * This is the only "bulk scan" in RTBridge, and it only runs once per
 * chunk load — not every frame.  After load, incremental LIGHT_ADD /
 * LIGHT_REMOVE events keep the cache up to date.
 */
@Mixin(net.minecraft.world.chunk.WorldChunk.class)
public class MixinLevelChunk {

    /**
     * Called after a chunk is fully initialised on the client.
     * Walk all block positions and register emissive blocks.
     */
    @Inject(
        method  = "<init>(Lnet/minecraft/world/World;" +
                  "Lnet/minecraft/util/math/ChunkPos;" +
                  "Lnet/minecraft/world/chunk/UpgradeData;J" +
                  "Ljava/util/Map;" +
                  "[Lnet/minecraft/world/chunk/ChunkSection;)V",
        at      = @At("TAIL")
    )
    private void rtbridge$onChunkInit(CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;

        net.minecraft.world.chunk.WorldChunk chunk =
            (net.minecraft.world.chunk.WorldChunk)(Object) this;

        // Iterate block positions and register lights
        // We defer this to an async task to avoid stalling chunk init
        // TODO: iterate chunk sections, filter getLuminance() > 0 blocks,
        //       post LIGHT_ADD events for each.
        //
        // Example:
        // for (ChunkSection section : chunk.getSectionArray()) {
        //     if (section == null || section.isEmpty()) continue;
        //     BlockBox sectionBounds = ...;
        //     for (BlockPos pos : BlockPos.iterate(sectionBounds)) {
        //         BlockState state = chunk.getBlockState(pos);
        //         if (state.getLuminance() > 0) {
        //             RTBridgeMod.getDirtyEventSystem().postLightAdd(pos.toImmutable());
        //         }
        //     }
        // }
    }
}
