package com.nightstar.bloom.mixin;

import com.nightstar.bloom.AxModelState;
import com.nightstar.bloom.BloomRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// AX model keys and vanilla fallback detection; GUI diagnostics are opt-in.
@Mixin(value = ItemRenderState.class, priority = 900)
public class ItemRenderStateMixin implements AxModelState {
    @Unique private String nightstar$axModel;
    @Unique private boolean nightstar$vanillaDrawn;
    @Unique private ItemStack nightstar$axStack;
    @Unique private ItemDisplayContext nightstar$context;
    // Diagnostic: counts every call, so "our injector never fires" is distinguishable from "AX passes no geo key".
    @Inject(method = "addModelKey", at = @At("HEAD"))
    private void nightstar$captureModelKey(Object key, CallbackInfo ci) {
        if (key instanceof String model && model.startsWith("arcartx_geo:")) nightstar$axModel = model;
        if (!BloomRenderer.diagnostics) return;
        BloomRenderer.diagKeyAll++;
        if (nightstar$context == ItemDisplayContext.GUI) BloomRenderer.diagKeyGuiAll++;
        boolean isString = key instanceof String;
        if (isString) BloomRenderer.diagKeyStr++; else BloomRenderer.diagKeyOther++;
        if (isString && ((String) key).startsWith("arcartx_geo:")) {
            BloomRenderer.diagKeyGeo++;
            if (nightstar$context == ItemDisplayContext.GUI) BloomRenderer.diagGuiKey++;
            else BloomRenderer.diagKeyGeoOther++;
            if (BloomRenderer.diagGeoKeySample == null) {
                BloomRenderer.diagGeoKeySample = (String) key + "@" + nightstar$context;
            }
        } else if (!isString && BloomRenderer.diagKeyOdd == null) {
            BloomRenderer.diagKeyOdd = (key == null ? "null" : key.getClass().getName());
        }
    }
    @Inject(method = "clear", at = @At("HEAD"))
    private void nightstar$clearModel(CallbackInfo ci) { nightstar$axModel = null; }
    // Observe only diagnostic traffic here; animation poses come from the AX draw tap.
    @Inject(method = "render", at = @At("HEAD"), order = 900)
    private void nightstar$observeAx(CallbackInfo ci) {
        String key = nightstar$axModel;ItemStack stack = nightstar$axStack;
        if (BloomRenderer.diagnostics) {
        if (nightstar$context == ItemDisplayContext.GUI) {
            BloomRenderer.diagGuiHead++;
            if (stack != null) {
                BloomRenderer.diagGuiStack++;
                // A GUI stack that still carries custom_data.model but reached render with no geo key is the exact
                // shape of "AX models this item yet never keyed it", as opposed to "this is simply another item".
                if (key == null && BloomRenderer.hasModelData(stack)) {
                    BloomRenderer.diagGuiModelNoKey++;
                    if (BloomRenderer.diagGuiModelSample == null) {
                        var d = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                        BloomRenderer.diagGuiModelSample = stack.getItem().toString() + ":"
                            + (d == null ? "-" : d.copyNbt().getString("model", "-"));
                    }
                }
            }
            if (key != null) BloomRenderer.diagGuiGeo++;
        } else BloomRenderer.diagOtherHead++;
        }

    }
    // RETURN injectors target the original return opcodes, so a HEAD cancel (ArcartX drawing the item) never reaches this.
    @Inject(method = "render", at = @At("RETURN"))
    private void nightstar$drawn(CallbackInfo ci) {
        nightstar$vanillaDrawn = true;
        if (BloomRenderer.diagnostics && nightstar$context == ItemDisplayContext.GUI) {
            BloomRenderer.diagGuiRet++;
            if (nightstar$axModel != null) BloomRenderer.diagGuiGeoRet++;
        }
    }
    @Override public void nightstar$context(ItemDisplayContext context) { nightstar$context = context; }
    @Override public ItemDisplayContext nightstar$context() { return nightstar$context; }
    @Override public String nightstar$axModel() { return nightstar$axModel; }
    @Override public void nightstar$axModel(String model) { nightstar$axModel = model; }
    @Override public boolean nightstar$vanillaDrawn() { return nightstar$vanillaDrawn; }
    @Override public void nightstar$vanillaDrawn(boolean drawn) { nightstar$vanillaDrawn = drawn; }
    @Override public void nightstar$axStack(ItemStack stack) { nightstar$axStack = stack; }
}
