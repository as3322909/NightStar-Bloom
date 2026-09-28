package com.nightstar.bloom;

import java.lang.invoke.*;
import net.minecraft.client.util.math.MatrixStack;

/** Optional AX 2.6.72 adapter: reads the matrix AX is about to use, without evaluating animation. */
public final class AxPoseAdapter {
    public static final String NODE="priv.seventeen.artist.arcartx.arcartxooooOOOOooOoOOooOOoOoooooOOooooOOOooOoooOOOOoOOoooOOoOoOOOOO";
    public static final String BONE="priv.seventeen.artist.arcartx.arcartxOooooOoooooOoooOOOOOooooooOoOOOooOooooOOooooOoOOoOooOoooooOO";
    public static String state="not-installed";
    private static MethodHandle boneGetter,nameGetter;
    private static boolean failed;
    private AxPoseAdapter(){}
    public static void capture(Object node,MatrixStack stack,boolean visible){
        if(failed||!BloomRenderer.INSTANCE.wantsAxPose())return;
        try{
            if(boneGetter==null){
                var field=node.getClass().getDeclaredField("ooooooOooOoOoOoooOooooOOOOOoOooOoOOoOooOOOOOooOooOOoOOOOoooo");field.setAccessible(true);
                var name=field.getType().getDeclaredField("0OO0OO0O0OO000000O000O0O0OO0O0O0OOO0OO0O00O0OO00OO0OOOO0OOO0");name.setAccessible(true);
                boneGetter=MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(Object.class,Object.class));
                nameGetter=MethodHandles.lookup().unreflectGetter(name).asType(MethodType.methodType(String.class,Object.class));
            }
            Object bone=(Object)boneGetter.invokeExact(node);
            BloomRenderer.INSTANCE.captureAxBone((String)nameGetter.invokeExact(bone),visible?null:stack.peek().getPositionMatrix(),visible);
            state="same-draw";
        }catch(Throwable e){if(e instanceof VirtualMachineError v)throw v;failed=true;state="adapter-failed: "+e;BloomClient.LOG.error("AX pose adapter disabled; model rendering is unchanged",e);}
    }
}
