package com.nightstar.bloom.mixin;

import com.nightstar.bloom.BloomRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary diagnostic: the 4-arg render is declared here (the outer class), never on the item subclass, and it
 * reaches the 5-arg override through the virtual call at the end of its body. Counting it separates "the item
 * display was never drawn this frame" (culled, or updateRenderState never ran) from "drawn but the callback
 * swallowed the result". Counts every display entity in view, so it is a superset of the item-only counters.
 * Remove with the other diag counters.
 */
@Mixin(DisplayEntityRenderer.class)
public class DisplayRendererMixin {
    private static final String RENDER = "render(Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void nightstar$diagAncestor(DisplayEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
        if(BloomRenderer.diagnostics)BloomRenderer.diagAncestor++;
    }
}
