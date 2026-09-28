package com.nightstar.bloom.mixin;
import com.nightstar.bloom.BloomProfiler;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftClient.class)
public class FrameMixin {
 @Inject(method="render",at=@At("HEAD")) private void sample(boolean tick,CallbackInfo ci){BloomProfiler.frame();com.nightstar.bloom.BloomRenderer.INSTANCE.frame();}
}
