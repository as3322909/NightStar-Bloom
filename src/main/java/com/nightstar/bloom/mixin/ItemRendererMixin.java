package com.nightstar.bloom.mixin;
import com.nightstar.bloom.BloomState;
import com.nightstar.bloom.BloomRenderer;
import com.nightstar.bloom.AxModelState;
import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.render.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.component.DataComponentTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Captures vanilla display meshes after the display renderer's single Y180 turn.
 * AX submissions, including item displays, belong exclusively to ArcartXRenderMixin.
 *
 * Which body is on screen is decided by what actually rendered, not by the model key: ArcartX tags every
 * item whose custom_data.model is non-empty, loaded or not, and only cancels ItemRenderState.render when it
 * really draws the model. Exactly one path is captured per object:
 *   vanilla ran, no AX key  -> item_model mesh
 *   vanilla ran, AX key     -> item_model mesh (AX model missing; counted as axFallback)
 *   cancelled, AX key       -> already handled by the AX draw hook; never submit twice here
 *   cancelled, no AX key    -> nothing (another mod replaced the item)
 */
@Mixin(DisplayEntityRenderer.ItemDisplayEntityRenderer.class)
public class ItemRendererMixin {
    @Inject(method="updateRenderState(Lnet/minecraft/entity/decoration/DisplayEntity$ItemDisplayEntity;Lnet/minecraft/client/render/entity/state/ItemDisplayEntityRenderState;F)V",at=@At("RETURN"))
    private void state(DisplayEntity.ItemDisplayEntity entity,ItemDisplayEntityRenderState state,float delta,CallbackInfo ci){
        if(BloomRenderer.diagnostics)BloomRenderer.diagSeen++;
        BloomState bloom=(BloomState)state;
        bloom.bloom$instance(entity.getUuid().hashCode());
        var stack=entity.getItemStack();
        bloom.bloom$model(stack.get(DataComponentTypes.ITEM_MODEL));

    }
    private static final String RENDER="render(Lnet/minecraft/client/render/entity/state/ItemDisplayEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;IF)V";
    @Inject(method=RENDER,at=@At("HEAD"))
    private void reset(ItemDisplayEntityRenderState state,MatrixStack matrices,OrderedRenderCommandQueue queue,int light,float delta,CallbackInfo ci){
        if(BloomRenderer.diagnostics)BloomRenderer.diagHead++;
        if(state.itemRenderState instanceof AxModelState s)s.nightstar$vanillaDrawn(false);
    }
    @Inject(method=RENDER,at=@At("RETURN"))
    private void capture(ItemDisplayEntityRenderState state,MatrixStack matrices,OrderedRenderCommandQueue queue,int light,float delta,CallbackInfo ci){
        if(BloomRenderer.diagnostics)BloomRenderer.diagRet++;
        if(!(state.itemRenderState instanceof AxModelState ax)){if(BloomRenderer.diagnostics)BloomRenderer.diagNoState++;return;}
        BloomState bloom=(BloomState)state;
        String geo=ax.nightstar$axModel();
        if(ax.nightstar$vanillaDrawn()){if(BloomRenderer.diagnostics)BloomRenderer.diagVanilla++;BloomRenderer.INSTANCE.capture(bloom.bloom$model(),matrices.peek().getPositionMatrix(),bloom.bloom$instance(),geo!=null);}
        // AX draws are captured exactly once inside ArcartXRenderMixin, including displays.
        else if(geo==null&&BloomRenderer.diagnostics)BloomRenderer.diagNoGeo++;
    }
}
