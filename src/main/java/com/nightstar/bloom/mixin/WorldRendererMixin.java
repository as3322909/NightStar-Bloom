package com.nightstar.bloom.mixin;
import com.nightstar.bloom.BloomRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.*;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void begin(ObjectAllocator allocator,RenderTickCounter ticks,boolean outline,Camera camera,Matrix4f view,Matrix4f projection,Matrix4f culling,GpuBufferSlice fog,Vector4f fogColor,boolean sky,CallbackInfo ci){BloomRenderer.INSTANCE.begin(view,projection);}
    @Inject(method="render",at=@At("RETURN"),order=2000)
    private void end(ObjectAllocator allocator,RenderTickCounter ticks,boolean outline,Camera camera,Matrix4f view,Matrix4f projection,Matrix4f culling,GpuBufferSlice fog,Vector4f fogColor,boolean sky,CallbackInfo ci){BloomRenderer.INSTANCE.endWorldCapture();BloomRenderer.INSTANCE.render();}
}
