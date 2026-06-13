package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldChunk.class)
public class MixinLevelChunk {
    // 区块加载时扫描发光方块 — 由 DirtyEventSystem Fabric hooks 处理
}
