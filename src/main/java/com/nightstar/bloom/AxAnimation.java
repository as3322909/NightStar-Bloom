package com.nightstar.bloom;

import com.google.gson.*;
import org.joml.Matrix4f;
import java.io.IOException;

/**
 * Replays an AX item model's idle animation for Bloom's own glow mesh (manifest v4 "rigs", written by
 * tools/export_gallery.py via tools/ax_anim.py; offline reference: tools/test_ax_anim.py).
 *
 * AX (AX-Fabric 2.6.72) is not hooked: it exposes no phase,
 * so Bloom keeps a clock per AX animation manager key and advances it with the same rules:
 *  - one manager per stack hash (item class, components, count), started at its first render, never evicted;
 *  - tick = GLFW seconds * 60 minus the first-render time, frozen only while the game is paused;
 *  - controller "default": 5-tick linear transition from rest to the value at tick 0, then RUNNING;
 *    a loop restarts from the current frame (not modulo), hold stays on the last frame.
 * Segment easing (linear / step / catmullrom) was already resolved by the exporter exactly as AX parses it.
 */
public final class AxAnimation {
    private AxAnimation(){}
    static final double TRANSITION=5.0;
    private static final int SEG=14;

    /** One bone channel: flat segments [len, start3, end3, mode, p0_3, p3_3]. */
    static final class Channel{
        final double[] s;final float[] start=new float[3];
        Channel(double[] s){this.s=s;
            // start value of the point found at tick 0 (target of the transition)
            int pick=s.length-SEG;double total=0;
            for(int i=0;i<s.length;i+=SEG){total+=s[i];if(total>0){pick=i;break;}}
            for(int a=0;a<3;a++)start[a]=(float)s[pick+1+a];
        }
        /** GeckoLib 4 getAnimationPointAtTick + AnimationPoint lerp. */
        void sample(double tick,float[] out){
            double total=0;int at=s.length-SEG;double local=tick;
            for(int i=0;i<s.length;i+=SEG){total+=s[i];if(total>tick){at=i;local=tick-(total-s[i]);break;}}
            double len=s[at];
            if(len<=0||local>=len){for(int a=0;a<3;a++)out[a]=(float)s[at+4+a];return;}
            double x=local/len;int mode=(int)s[at+7];
            for(int a=0;a<3;a++){
                double p1=s[at+1+a],p2=s[at+4+a];
                out[a]=(float)switch(mode){
                    case 1->p1;
                    case 2->{double p0=s[at+8+a],p3=s[at+11+a];yield 0.5*(2*p1+(p2-p0)*x+(2*p0-5*p1+4*p2-p3)*x*x+(3*p1-p0-3*p2+p3)*x*x*x);}
                    default->p1+(p2-p1)*x;
                };
            }
        }
    }
    static final class Bone{
        int parent;final float[] pivot=new float[3],rest=new float[3];Channel rot,pos,scale;
    }
    /** Parsed rig; the scratch matrices make evaluation allocation-free (render thread only). */
    static final class Rig{
        final String id;final Matrix4f base=new Matrix4f();double length;boolean loop;Bone[] bones;Matrix4f[] pose;
        private final float[] r=new float[3],p=new float[3],k=new float[3];
        Rig(String id){this.id=id;}
        static Rig parse(String id,JsonObject o)throws IOException{
            Rig rig=new Rig(id);
            JsonArray b=o.getAsJsonArray("base");if(b==null||b.size()!=16)throw new IOException("rig "+id+": base must be 16 floats");
            float[] m=new float[16];for(int i=0;i<16;i++){m[i]=b.get(i).getAsFloat();if(!Float.isFinite(m[i]))throw new IOException("rig "+id+": non-finite base");}
            rig.base.set(m).transpose(); // exporter writes row-major
            rig.length=o.get("length").getAsDouble();
            if(!Double.isFinite(rig.length)||rig.length<0||rig.length>1e6)throw new IOException("rig "+id+": invalid length");
            String loop=o.get("loop").getAsString();
            if(!loop.equals("loop")&&!loop.equals("hold"))throw new IOException("rig "+id+": unsupported loop "+loop);
            rig.loop=loop.equals("loop");
            JsonArray bones=o.getAsJsonArray("bones");if(bones==null||bones.isEmpty()||bones.size()>4096)throw new IOException("rig "+id+": invalid bones");
            rig.bones=new Bone[bones.size()];rig.pose=new Matrix4f[bones.size()];
            for(int i=0;i<bones.size();i++){
                JsonObject j=bones.get(i).getAsJsonObject();Bone bone=new Bone();
                bone.parent=j.get("parent").getAsInt();
                if(bone.parent<-1||bone.parent>=i)throw new IOException("rig "+id+": bone "+i+" parent must precede it");
                vec(j,"pivot",bone.pivot,id);vec(j,"rest",bone.rest,id);
                bone.rot=channel(j,"rot",id);bone.pos=channel(j,"pos",id);bone.scale=channel(j,"scale",id);
                rig.bones[i]=bone;rig.pose[i]=new Matrix4f();
            }
            return rig;
        }
        private static void vec(JsonObject j,String key,float[] out,String id)throws IOException{
            JsonArray a=j.getAsJsonArray(key);if(a==null||a.size()!=3)throw new IOException("rig "+id+": invalid "+key);
            for(int i=0;i<3;i++){out[i]=a.get(i).getAsFloat();if(!Float.isFinite(out[i]))throw new IOException("rig "+id+": non-finite "+key);}
        }
        private static Channel channel(JsonObject j,String key,String id)throws IOException{
            JsonElement e=j.get(key);if(e==null||e.isJsonNull())return null;
            JsonArray a=e.getAsJsonArray();if(a.isEmpty()||a.size()%SEG!=0||a.size()>SEG*100000)throw new IOException("rig "+id+": invalid channel "+key);
            double[] s=new double[a.size()];
            for(int i=0;i<s.length;i++){s[i]=a.get(i).getAsDouble();if(!Double.isFinite(s[i]))throw new IOException("rig "+id+": non-finite channel "+key);}
            for(int i=0;i<s.length;i+=SEG){int mode=(int)s[i+7];if(s[i]<0||mode<0||mode>2||mode!=s[i+7])throw new IOException("rig "+id+": invalid segment "+key);}
            return new Channel(s);
        }
        private static void value(Channel c,double tick,double blend,float rest,float[] out){
            if(c==null){out[0]=out[1]=out[2]=rest;return;}
            if(blend>=0){for(int a=0;a<3;a++)out[a]=(float)(rest+(c.start[a]-rest)*blend);return;}
            c.sample(tick,out);
        }
        /** Fills {@link #pose} with base · bone matrices; blend in [0,1) = transition, otherwise animation tick. */
        void evaluate(double tick,double blend){
            for(int i=0;i<bones.length;i++){
                Bone b=bones[i];
                value(b.rot,tick,blend,0,r);value(b.pos,tick,blend,0,p);value(b.scale,tick,blend,1,k);
                Matrix4f m=pose[i];
                if(b.parent<0)m.set(base);else m.set(pose[b.parent]);
                m.translate(p[0]/16f,p[1]/16f,p[2]/16f).translate(b.pivot[0],b.pivot[1],b.pivot[2])
                    .rotateZ((float)Math.toRadians(b.rest[2]+r[2])).rotateY((float)Math.toRadians(b.rest[1]+r[1])).rotateX((float)Math.toRadians(b.rest[0]+r[0]))
                    .scale(k[0],k[1],k[2]).translate(-b.pivot[0],-b.pivot[1],-b.pivot[2]);
            }
        }
    }
    /** What a clock needs to advance: kept apart from the rig so clocks run while Bloom's meshes are unloaded. */
    record Timing(double length,boolean loop){}
    /** Mirror of one AX animation manager + its "default" controller. */
    static final class Clock{
        boolean started,transitioning;double first,tick,offset,adj;
        /** Last frame AX rendered this manager; last skin-stream frame and binding Bloom skinned from it. */
        long axFrame=-1,steppedFrame=-1;Object owner;int[] firsts;
        /**
         * Called for every frame AX itself renders this manager, even while Bloom is off or in a context Bloom
         * does not draw in (GUI, hotbar, other entities). The phase depends on exactly which frames AX rendered:
         * the start is the first render, and a loop restarts from the first rendered frame past the end (not
         * modulo). A clock that only ran while Bloom drew would therefore sit at a permanent phase offset.
         * One step per frame, like AX's same-frame early return.
         */
        void observe(long frame,double now,boolean paused,double length,boolean loop){
            if(frame<=axFrame)return; // deferred observations may arrive after this frame was already stepped
            axFrame=frame;
            if(!started){started=true;first=now;tick=0;offset=0;transitioning=true;}
            else if(!paused)tick=now-first;
            adj=Math.max(tick-offset,0);
            if(transitioning){
                if(adj<TRANSITION)return;
                transitioning=false;offset=tick;adj=0;
            }
            if(adj>=length&&loop){offset=tick;adj=0;}
        }
        /** Poses the rig at the state of the last {@link #observe}. */
        void pose(Rig rig){
            if(transitioning)rig.evaluate(0,adj/TRANSITION);else rig.evaluate(adj,-1);
        }
    }
}
