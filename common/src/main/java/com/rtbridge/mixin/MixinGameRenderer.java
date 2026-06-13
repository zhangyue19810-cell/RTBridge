package com.rtbridge.mixin;

import com.rtbridge.RTBridgeMod;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void rtbridge$onRenderWorldEnd(CallbackInfo ci) {
        // RT 提交由 WorldRenderEvents 处理
    }
}
