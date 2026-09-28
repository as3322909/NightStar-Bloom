package com.nightstar.bloom;

import org.joml.Matrix4f;
import java.util.*;

/** Render-thread scratch snapshot. Valid only for one display draw and one resource generation. */
public final class AxPose {
    private final Map<String,Integer> indices=new HashMap<>();
    private final Matrix4f[] matrices;
    private final long[] stamps;
    private final boolean[] visible;
    private static final Matrix4f HIDDEN=new Matrix4f().zero();
    private long invocation;
    private int generation;
    private Object owner;
    private boolean invalid;
    public AxPose(String[] names){
        matrices=new Matrix4f[names.length];stamps=new long[names.length];visible=new boolean[names.length];
        for(int i=0;i<names.length;i++){
            if(names[i]==null||names[i].isBlank()||indices.put(names[i],i)!=null)throw new IllegalArgumentException("Missing or duplicate AX bone name; regenerate manifest");
            matrices[i]=new Matrix4f();
        }
    }
    public void begin(Object owner,int generation,long invocation){this.owner=owner;this.generation=generation;this.invocation=invocation;invalid=false;}
    public boolean capture(String name,Matrix4f value){
        Integer i=indices.get(name);if(i==null)return false;
        if(stamps[i]==invocation||!value.isFinite()){invalid=true;return false;}
        matrices[i].set(value);stamps[i]=invocation;visible[i]=true;return true;
    }
    public void hide(String name){Integer i=indices.get(name);if(i!=null)visible[i]=false;}
    public void reveal(String name){Integer i=indices.get(name);if(i!=null&&stamps[i]==invocation)visible[i]=true;}
    public void reveal(String name,Matrix4f value){
        Integer i=indices.get(name);
        if(i!=null&&stamps[i]==invocation&&!invalid){
            if(!value.isFinite()){invalid=true;return;}
            matrices[i].set(value);visible[i]=true;
        }
    }
    public Matrix4f get(int bone,Object owner,int generation,long invocation){
        return !invalid&&this.owner==owner&&this.generation==generation&&this.invocation==invocation&&stamps[bone]==invocation?(visible[bone]?matrices[bone]:HIDDEN):null;
    }
    public int size(){return matrices.length;}
    public void clear(){owner=null;invocation=0;Arrays.fill(stamps,0);}
}
