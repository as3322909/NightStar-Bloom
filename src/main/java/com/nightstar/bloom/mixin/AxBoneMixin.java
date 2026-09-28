package com.nightstar.bloom.mixin;

import com.nightstar.bloom.AxPoseAdapter;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Optional, fingerprint-gated AX draw tap. The pose includes all parent and display transforms. */
@Pseudo
@Mixin(targets=AxPoseAdapter.NODE,remap=false)
public class AxBoneMixin {
    @Inject(method="ooooooOooOoOoOoooOooooOOOOOoOooOoOOoOooOOOOOooOooOOoOOOOoooo(Lnet/minecraft/class_4587;Lpriv/seventeen/artist/arcartx/arcartxOOOOoOoooOOOoOoOoOoOoOOOooooOOOooOOOOooOOooOOOoOoOooOOoOOooO;Ljava/util/List;IIFLpriv/seventeen/artist/arcartx/arcartxo00ooooo0ooo00ooo0oo0o00o0000000000000oo00ooo00ooo00o0oo0000;I)V",at=@At("HEAD"),require=0,remap=false)
    private void nightstar$pose(MatrixStack stack,@Coerce Object buffer,List<?> extras,int light,int overlay,float alpha,@Coerce Object context,int extraCount,CallbackInfo ci){AxPoseAdapter.capture(this,stack,false);}
    @Inject(method="ooooooOooOoOoOoooOooooOOOOOoOooOoOOoOooOOOOOooOooOOoOOOOoooo(Lnet/minecraft/class_4587;Lpriv/seventeen/artist/arcartx/arcartxOOOOoOoooOOOoOoOoOoOoOOOooooOOOooOOOOooOOooOOOoOoOooOOoOOooO;Ljava/util/List;IIFLpriv/seventeen/artist/arcartx/arcartxo00ooooo0ooo00ooo0oo0o00o0000000000000oo00ooo00ooo00o0oo0000;I)V",at=@At(value="INVOKE",target="Ljava/util/List;size()I",ordinal=0),require=0,remap=false)
    // AX reuses argument local slots inside this method. A mid-method callback must not load them.
    private void nightstar$visible(CallbackInfo ci){AxPoseAdapter.capture(this,null,true);}
}
