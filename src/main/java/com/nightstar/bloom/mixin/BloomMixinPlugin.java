package com.nightstar.bloom.mixin;

import com.nightstar.bloom.AxPoseAdapter;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import java.util.*;
import java.security.MessageDigest;

/** Never applies the internal AX hook to an unverified binary. No AX classes are loaded by this probe. */
public final class BloomMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String pkg){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        if(!mixin.endsWith(".AxBoneMixin")&&!mixin.endsWith(".ArcartXRenderMixin"))return true;
        var mod=FabricLoader.getInstance().getModContainer("arcartx");
        if(mod.isEmpty()){AxPoseAdapter.state="absent";return false;}
        if(!mod.get().getMetadata().getVersion().getFriendlyString().equals("2.6.72")){AxPoseAdapter.state="unsupported-version";return false;}
        try{
            String[] names={AxPoseAdapter.NODE,AxPoseAdapter.BONE};
            String[] hashes={"e141a1c9b995f5fdeabd8c0867b5250ae42174f367f28e3d0e3fe23670d4737f","35017d7c8d9c12fd6cc5fa1dfc2d7b4a855ed8a5fd9ebd3b818f48e2d447e16c"};
            for(int i=0;i<names.length;i++){
                var path=mod.get().findPath(names[i].replace('.','/')+".class").orElseThrow();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
                if(!hash.equals(hashes[i])){AxPoseAdapter.state="contract-mismatch";return false;}
            }
            AxPoseAdapter.state="awaiting-draw";return true;
        }catch(Exception e){AxPoseAdapter.state="contract-unavailable";return false;}
    }
    public void acceptTargets(Set<String> mine,Set<String> other){}
    public List<String> getMixins(){return null;}
    public void preApply(String n,ClassNode c,String m,IMixinInfo i){}
    public void postApply(String n,ClassNode c,String m,IMixinInfo i){}
}
