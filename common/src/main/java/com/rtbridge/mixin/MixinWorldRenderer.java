package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.event.DirtyEvent;
import com.rtbridge.event.DirtyEventType;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Inject(method = "addEntity", at = @At("TAIL"))
    private void rtbridge$onEntityAdded(Entity entity, CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;
        RTBridgeMod.getDirtyEventSystem().post(
            DirtyEvent.of(DirtyEventType.ENTITY_SPAWN).entity(entity.getId()).build());
    }

    @Inject(method = "removeEntity", at = @At("TAIL"))
    private void rtbridge$onEntityRemoved(Entity entity, boolean keepTrackingStatus, CallbackInfo ci) {
        if (RTBridgeMod.getDirtyEventSystem() == null) return;
        RTBridgeMod.getDirtyEventSystem().post(
            DirtyEvent.of(DirtyEventType.ENTITY_REMOVE).entity(entity.getId()).build());
    }
}
