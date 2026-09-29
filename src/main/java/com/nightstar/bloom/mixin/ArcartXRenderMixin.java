package com.nightstar.bloom.mixin;

import com.nightstar.bloom.BloomRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures AX's real world item draw for third-person hands, equipment and displays.
 *
 * ItemRenderState.render is the vanilla dispatch point, but AX cancels it from its own
 * ItemStackRenderStateMixin after calling this hook.  The display-entity mixin therefore
 * cannot observe player-held or equipped items.  Tapping the AX hook keeps the exact pose
 * stack and lets bone taps run between begin and capture, even for unanimated BB models.
 * The hook restores its input stack before returning. Captured bone matrices already
 * include the selected display context; skinning cancels the root exactly once.
 * GUI and first-person passes have different projections and are not world submissions.
 */
@Pseudo
@Mixin(targets = "priv.seventeen.artist.arcartx.fabric.core.ArcartXHook", remap = false)
public final class ArcartXRenderMixin {
    private static final String RENDER_ITEM =
        "renderItem(Lnet/minecraft/class_1799;Lnet/minecraft/class_811;Lnet/minecraft/class_4587;"
        + "Lnet/minecraft/class_4597;Lnet/minecraft/class_11785;IIZ)Z";

    @Inject(method = RENDER_ITEM, at = @At("HEAD"), require = 0, remap = false)
    private static void nightstar$begin(ItemStack stack, ItemDisplayContext context, MatrixStack matrices,
                                        VertexConsumerProvider source, @Nullable RenderCommandQueue queue,
                                        int light, int overlay, boolean foil, CallbackInfoReturnable<Boolean> cir) {
        if (nightstar$worldItem(context))
            BloomRenderer.INSTANCE.beginAxDraw(BloomRenderer.axModelKey(stack), matrices);
    }

    @Inject(method = RENDER_ITEM, at = @At("RETURN"), require = 0, remap = false)
    private static void nightstar$capture(ItemStack stack, ItemDisplayContext context, MatrixStack matrices,
                                          VertexConsumerProvider source, @Nullable RenderCommandQueue queue,
                                          int light, int overlay, boolean foil, CallbackInfoReturnable<Boolean> cir) {
        if (!nightstar$worldItem(context)) return;
        if (cir.getReturnValueZ()) {
            String key = BloomRenderer.axModelKey(stack);
            if (key != null) {
                BloomRenderer.INSTANCE.captureAxItem(key, matrices.peek().getPositionMatrix(),
                    System.identityHashCode(stack), matrices, context == ItemDisplayContext.FIXED);
            }
        }
        BloomRenderer.INSTANCE.endAxDraw();
    }

    private static boolean nightstar$worldItem(ItemDisplayContext context) {
        return BloomRenderer.INSTANCE.acceptsAxItems() && context != ItemDisplayContext.GUI
            && context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            && context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
    }
}
