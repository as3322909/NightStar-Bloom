package com.nightstar.bloom.mixin;

import com.nightstar.bloom.AxModelState;
import com.nightstar.bloom.BloomRenderer;
import net.minecraft.client.render.item.KeyedItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeyedItemRenderState overrides ItemRenderState.addModelKey with its own body, so the base-class handler in
 * ItemRenderStateMixin never sees a call on one of these. The 2026-09-29 tagged-inventory probe confirmed
 * geoGui > 0 with base guiAll = 0 (docs/AX-GLOW-DIAGNOSTIC-STATUS.md). A tagged, open GUI with keyed all = 0
 * would invalidate the hypothesis; empty scenes cannot decide it.
 * Store the same raw key in the inherited state for consistent AX identification across display contexts.
 * This does not submit GUI geometry to the world's Bloom pass. The base clear() hook clears this field.
 */
@Mixin(value = KeyedItemRenderState.class, priority = 900)
public class KeyedItemRenderStateMixin {
    @Inject(method = "addModelKey", at = @At("HEAD"))
    private void nightstar$captureKeyedModelKey(Object key, CallbackInfo ci) {
        if (key instanceof String model && model.startsWith("arcartx_geo:")) {
            ((AxModelState) this).nightstar$axModel(model);
        }
        if (!BloomRenderer.diagnostics) return;
        BloomRenderer.diagKeyKeyed++;
        boolean isGeo = key instanceof String && ((String) key).startsWith("arcartx_geo:");
        ItemDisplayContext context = ((AxModelState) this).nightstar$context();
        if (context == ItemDisplayContext.GUI) {
            BloomRenderer.diagKeyKeyedGui++;
            if (isGeo) {
                BloomRenderer.diagKeyKeyedGeoGui++;
                if (BloomRenderer.diagKeyedSample == null) BloomRenderer.diagKeyedSample = key + "@" + context;
            }
        } else if (isGeo) {
            BloomRenderer.diagKeyKeyedGeo++;
            if (BloomRenderer.diagKeyedSample == null) BloomRenderer.diagKeyedSample = key + "@" + context;
        }
    }
}
