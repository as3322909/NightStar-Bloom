package com.nightstar.bloom.mixin;
import com.nightstar.bloom.BloomState;
import net.minecraft.client.render.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.*;
@Mixin(ItemDisplayEntityRenderState.class)
public class ItemStateMixin implements BloomState {
    @Unique private int bloom$instance;
    public int bloom$instance(){return bloom$instance;}
    public void bloom$instance(int id){bloom$instance=id;}
    @Unique private Identifier bloom$id;
    public Identifier bloom$model(){return bloom$id;}
    public void bloom$model(Identifier id){bloom$id=id;}
    @Unique private int bloom$axHash;
    public int bloom$axHash(){return bloom$axHash;}
    public void bloom$axHash(int hash){bloom$axHash=hash;}
}
