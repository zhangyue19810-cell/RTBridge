package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.event.DirtyEvent;
import com.rtbridge.event.DirtyEventType;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MixinWorldRenderer — entity lifecycle dirty events.
 *
 * Hooks into WorldRenderer to fire DirtyEvent when entities
 * are added or removed from the render list.
 *
 * We do NOT modify how entities are rendered — only observe the events.
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Inject(
        method = "addEntity(Lnet/minecraft/entity/Entity;)V",
        at     = @At("TAIL")
    )
    private void rtbridge$onEntityAdded(Entity entity, CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;
        RTBridgeMod.getDirtyEventSystem().post(
            DirtyEvent.of(DirtyEventType.ENTITY_SPAWN)
                .entity(entity.getId())
                .build()
        );
    }

    @Inject(
        method = "removeEntity(Lnet/minecraft/entity/Entity;Z)V",
        at     = @At("TAIL")
    )
    private void rtbridge$onEntityRemoved(Entity entity, boolean keepTrackingStatus,
                                           CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;
        RTBridgeMod.getDirtyEventSystem().post(
            DirtyEvent.of(DirtyEventType.ENTITY_REMOVE)
                .entity(entity.getId())
                .build()
        );
    }

    /**
     * Hook called when WorldRenderer detects a section / chunk needs to be
     * re-baked (e.g. due to a block change).
     * We fire a CHUNK_REMESH event so SceneExtractor updates the BLAS.
     */
    @Inject(
        method = "scheduleBlockRerenderIfNeeded(" +
                 "IIILnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V",
        at     = @At("HEAD")
    )
    private void rtbridge$onBlockChanged(int x, int y, int z,
                                          net.minecraft.block.BlockState oldState,
                                          net.minecraft.block.BlockState newState,
                                          CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        RTBridgeMod.getDirtyEventSystem().post(
            DirtyEvent.of(DirtyEventType.BLOCK_PLACE)
                .block(pos)
                .chunk(new net.minecraft.util.math.ChunkPos(pos))
                .build()
        );
    }
}
