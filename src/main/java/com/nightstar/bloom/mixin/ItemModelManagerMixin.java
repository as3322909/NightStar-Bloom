package com.nightstar.bloom.mixin;

import com.nightstar.bloom.AxModelState;
import com.nightstar.bloom.BloomRenderer;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the stack each render state was built from, at the same two entry points where ArcartX stores it
 * for its own render (ItemModelResolverMixin). ItemRenderStateMixin hashes it into AX's manager key.
 */
@Mixin(ItemModelManager.class)
public class ItemModelManagerMixin {
    /**
     * Diagnostic: ArcartX hangs its geo-key TAIL injector on clearAndUpdate only, so knowing which of the two entry
     * points the GUI hotbar uses decides whether the key can ever be written there. hasModel counts the stacks that
     * still carried custom_data.model when they arrived, which is what AX's guard tests.
     */
    @Inject(method = "clearAndUpdate", at = @At("HEAD"))
    private void nightstar$stackOnClear(ItemRenderState state, ItemStack stack, ItemDisplayContext context, @Nullable World world, @Nullable HeldItemContext owner, int seed, CallbackInfo ci) {
        ((AxModelState) state).nightstar$axStack(stack);
        ((AxModelState) state).nightstar$context(context);
        if (!BloomRenderer.diagnostics) return;
        BloomRenderer.diagClearUpdate++;
        if (context == ItemDisplayContext.GUI) {
            BloomRenderer.diagClearUpdateGui++;
            if (BloomRenderer.hasModelData(stack)) BloomRenderer.diagClearUpdateModel++;
            if (BloomRenderer.diagStateGuiClass == null) BloomRenderer.diagStateGuiClass = state.getClass().getName();
        } else if (BloomRenderer.diagStateOtherClass == null) {
            BloomRenderer.diagStateOtherClass = state.getClass().getName();
        }
    }
    @Inject(method = "update", at = @At("HEAD"))
    private void nightstar$stackOnUpdate(ItemRenderState state, ItemStack stack, ItemDisplayContext context, @Nullable World world, @Nullable HeldItemContext owner, int seed, CallbackInfo ci) {
        ((AxModelState) state).nightstar$axStack(stack);
        ((AxModelState) state).nightstar$context(context);
        if (!BloomRenderer.diagnostics) return;
        BloomRenderer.diagUpdate++;
        if (context == ItemDisplayContext.GUI) {
            BloomRenderer.diagUpdateGui++;
            if (BloomRenderer.hasModelData(stack)) BloomRenderer.diagUpdateModel++;
        }
    }
}
