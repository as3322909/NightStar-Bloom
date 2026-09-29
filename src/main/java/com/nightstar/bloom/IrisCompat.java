package com.nightstar.bloom;

import java.lang.invoke.*;
import net.fabricmc.loader.api.FabricLoader;

/** Optional public Iris API; no internal classes or shader-pack code are bundled. */
final class IrisCompat {
    static final boolean PRESENT=FabricLoader.getInstance().isModLoaded("iris");
    private static MethodHandle pack,shadow;
    private static boolean probed;
    static String error="";
    static void probe(){
        if(probed||!PRESENT)return;probed=true;
        try{
            Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance=api.getMethod("getInstance").invoke(null);
            pack=MethodHandles.publicLookup().unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance);
            shadow=MethodHandles.publicLookup().unreflect(api.getMethod("isRenderingShadowPass")).bindTo(instance);
        }catch(ReflectiveOperationException|LinkageError e){error=e.toString();BloomClient.LOG.warn("Iris public API unavailable",e);}
    }
    static boolean shaders(){return query(pack);}
    static boolean shadow(){return query(shadow);}
    private static boolean query(MethodHandle method){
        if(method==null)return false;
        try{return (boolean)method.invokeExact();}
        catch(Throwable e){if(e instanceof VirtualMachineError v)throw v;error=e.toString();pack=shadow=null;return false;}
    }
}
