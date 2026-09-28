package com.nightstar.bloom;

import com.google.gson.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.*;
import java.io.*;

/**
 * Three depth-tested emission layers, shared separable convolution, bounded linear-light composition.
 *
 * Resources are built into a staging {@link Resources} set and swapped in only when every manifest,
 * mesh and texture validated; a failed reload keeps the previous valid set. Manifests are parsed only
 * on reload, never per frame. Per-frame uniforms are written once into ring buffers that rotate once
 * per frame, so the CPU never waits on GPU work submitted in the same frame.
 */
public final class BloomRenderer implements AutoCloseable {
    public static final BloomRenderer INSTANCE=new BloomRenderer();
    /** captureAx results. */
    public static final int AX_CAPTURED=0,AX_UNMAPPED=1,AX_UNSUPPORTED=2;
    private static final int LIMIT=2048,PARAM_BYTES=96,DRAW_BYTES=128,MAX_POST=24,MAX_REPORTED=64,LOOKUP_CACHE=4096;
    private static final Set<String> SPACES=Set.of("item","ax"),RIGS=Set.of("static","rigid","anim","bones");
    /** Skinned vertices per frame (20 bytes each) and live AX clocks; beyond either, new objects get no Bloom. */
    private static final int SKIN_LIMIT=262144,CLOCK_LIMIT=4096;
    // AX never evicts its managers, so the mirrored clocks survive reloads and close() too.
    private final HashMap<Integer,AxAnimation.Clock> clocks=new HashMap<>();
    private static final class Observation { int hash; long frame; double time; boolean paused; AxAnimation.Timing timing; }
    private final ArrayList<Observation> observations=new ArrayList<>();
    private int observationCount,observationGeneration;
    // Animation timing per alias key (raw spellings cached like Resources.resolve); also kept across close() so
    // clocks keep following AX while Bloom is off. Only filled once a manifest generation has loaded.
    private final HashMap<String,AxAnimation.Timing> timings=new HashMap<>();
    private final HashMap<String,Optional<AxAnimation.Timing>> timingLookups=new HashMap<>();
    private ByteBuffer skinData;
    private MappableRingBuffer skinRing;
    private int skinVertices,skinned,skinOverflow;
    /** frameId: world renders (skin stream); clientFrame: MinecraftClient.render calls (AX's per-frame step). */
    private long frameId,clientFrame;
    private Resources res;
    private final ArrayList<Draw> draws=new ArrayList<>();
    private final Matrix4f vp=new Matrix4f(),identity=new Matrix4f(),mvp=new Matrix4f();
    private final Target[] masks=new Target[3];
    private final ArrayList<Target> down=new ArrayList<>(),up=new ArrayList<>();
    private Target scene,narrow,temp;
    private MappableRingBuffer postUniforms,drawUniforms;
    private ByteBuffer drawData,postData;
    private final Target[] postSrc=new Target[MAX_POST],postAux=new Target[MAX_POST],postOut=new Target[MAX_POST];
    private int count,w,h,levels,stride,postStride,capacity,postCount;
    private RenderPipeline maskPipeline,blurPipeline,combinePipeline;
    private GpuSampler linear,nearest;
    private boolean loadAttempted;
    private volatile boolean dirty=true;
    private final boolean iris=FabricLoader.getInstance().isModLoaded("iris");
    private final long epoch=System.nanoTime();
    public String failure="",loadError="",reloadState="pending";
    public long frames,passCount,drawCount;
    /**
     * Temporary diagnostic: forwards the injector fire counts from ItemRendererMixin. Remove together with
     * that class's counters once the item_display capture path is confirmed end to end.
     */
    /** Session-only diagnostics, disabled on every launch. No per-frame probe work while off. */
    public static boolean diagnostics;
    public static long diagSeen,diagAncestor,diagHead,diagRet,diagNoState,diagVanilla,diagNoGeo;
    /** Temporary diagnostic: ItemRenderState.render traffic split by display context (ItemRenderStateMixin). */
    public static long diagGuiHead,diagGuiRet,diagGuiStack,diagGuiGeo,diagGuiGeoRet,diagGuiKey,diagOtherHead;
    /** Temporary diagnostic: every ItemRenderState.addModelKey call, split by payload type (ItemRenderStateMixin). */
    public static long diagKeyAll,diagKeyStr,diagKeyOther,diagKeyGeo;
    /** Temporary diagnostic: how often a geo key was written while the context was or was not GUI. */
    public static long diagKeyGuiAll,diagKeyGeoOther;
    /** Temporary diagnostic: first non-geo key's class, and the first geo key as "key@CONTEXT". */
    public static String diagKeyOdd,diagGeoKeySample;
    /** Temporary diagnostic: GUI renders whose stack carried custom_data.model yet arrived with no geo key. */
    public static long diagGuiModelNoKey;
    /** Temporary diagnostic: "<item>:<model>" of the first such stack, naming which item AX refused to key. */
    public static String diagGuiModelSample;
    /** Temporary diagnostic: which ItemModelManager entry point builds GUI states, and whether the stack had model. */
    public static long diagClearUpdate,diagClearUpdateGui,diagClearUpdateModel,diagUpdate,diagUpdateGui,diagUpdateModel;
    /**
     * Temporary diagnostic: addModelKey calls that arrived through KeyedItemRenderState's override rather than the
     * base method. ArcartX renders GUI stacks through that subclass, so the base-class handler never sees them; the
     * keyed counters are what make the split visible. geoGui>0 with the base guiAll still 0 is the confirmation.
     */
    public static long diagKeyKeyed,diagKeyKeyedGui,diagKeyKeyedGeo,diagKeyKeyedGeoGui;
    /**
     * Temporary diagnostic: concrete class of the render state ArcartX hands to clearAndUpdate, per context, plus the
     * first keyed geo key seen with its context. KeyedItemRenderState at GUI is the mechanism the counters predict.
     */
    public static String diagStateGuiClass,diagStateOtherClass,diagKeyedSample;
    /** True when the stack carries the non-empty custom_data.model that ArcartX treats as a custom item model. */
    public static boolean hasModelData(ItemStack stack){
        if(stack==null||stack.isEmpty())return false;
        var d=stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        return d!=null&&!d.copyNbt().getString("model","").isEmpty();
    }
    public static void setDiagnostics(boolean enabled){
        diagnostics=enabled;
        if(!enabled)return;
        diagSeen=diagAncestor=diagHead=diagRet=diagNoState=diagVanilla=diagNoGeo=0;
        diagGuiHead=diagGuiRet=diagGuiStack=diagGuiGeo=diagGuiGeoRet=diagGuiKey=diagOtherHead=0;
        diagKeyAll=diagKeyStr=diagKeyOther=diagKeyGeo=0;
        diagKeyGuiAll=diagKeyGeoOther=0;
        diagKeyOdd=diagGeoKeySample=null;
        diagGuiModelNoKey=0;
        diagGuiModelSample=null;
        diagClearUpdate=diagClearUpdateGui=diagClearUpdateModel=diagUpdate=diagUpdateGui=diagUpdateModel=0;
        diagKeyKeyed=diagKeyKeyedGui=diagKeyKeyedGeo=diagKeyKeyedGeoGui=0;
        diagStateGuiClass=diagStateOtherClass=diagKeyedSample=null;
    }
    private int overflow,axCaptured,axFallback,axUnmapped,axUnsupported,generation;
    private final Set<String> reportedMisses=new HashSet<>();
    private double time;
    public void requestReload(){dirty=true;reloadState="pending";}
    public boolean disabledByIris(){return iris;}
    private void releaseSkinBindings(){
        for(AxAnimation.Clock c:clocks.values()){c.owner=null;c.firsts=null;c.steppedFrame=-1;}
    }
    public void begin(Matrix4f view,Matrix4f projection){
        count=0;overflow=0;axCaptured=axFallback=axUnmapped=axUnsupported=0;time=(System.nanoTime()-epoch)*1e-9;
        frameId++;skinVertices=skinned=skinOverflow=0;
        if(dirty){dirty=false;failure="";loadAttempted=false;}
        vp.set(projection).mul(view);
        if(!BloomClient.config.enabled||iris||!failure.isEmpty()||loadAttempted)return;
        loadAttempted=true;
        AxCompat.probe();
        Resources next=new Resources();
        try{
            load(next,MinecraftClient.getInstance().getResourceManager());
            Resources old=res;res=next;releaseSkinBindings();if(old!=null)old.close();
            timings.clear();timingLookups.clear();
            for(var b:next.bindings.entrySet())if(b.getValue().anim!=null)timings.put(b.getKey(),new AxAnimation.Timing(b.getValue().anim.length,b.getValue().anim.loop));
            generation++;loadError="";reportedMisses.clear();reloadState="complete";
            BloomClient.LOG.info("Bloom v0.3.0 generation {}: {} meshes / {} textures / {} AX bindings / {} aliases / {} conflicts",generation,next.meshCount,next.textures.size(),next.axModels.size(),next.aliasCount,next.conflicts.size());
            for(String c:next.conflicts)BloomClient.LOG.warn("Bloom manifest conflict: {}",c);
        }catch(Exception|LinkageError e){
            next.close();loadError=e.toString();
            reloadState=res!=null?"failed-kept-previous":"failed";
            BloomClient.LOG.error("Bloom manifest reload rejected; {}",res!=null?"keeping generation "+generation:"no valid generation loaded",e);
        }
    }
    /**
     * Called for an item whose render state carries an ArcartX geometry key, i.e. AX replaced the vanilla
     * item model. The AX mesh is drawn when the manifest declares it in AX model space with a rigid rig, or with
     * an anim rig (idle animation replayed on the CPU on a clock mirroring AX's manager for {@code axHash});
     * otherwise Bloom stays off for this object and the AX body renders untouched.
     */
    public int captureAx(String key,Matrix4f matrix,int instance,int axHash){
        if(!active())return AX_UNMAPPED;
        AxBinding binding=res.resolve(key);
        if(binding==null){
            axUnmapped++;
            if(reportedMisses.size()<MAX_REPORTED&&reportedMisses.add(key))BloomClient.LOG.info("Bloom: AX model {} has no Bloom binding; object left without Bloom",key);
            return AX_UNMAPPED;
        }
        if(!binding.rigid){
            axUnsupported++;
            if(reportedMisses.size()<MAX_REPORTED&&reportedMisses.add(key))BloomClient.LOG.warn("Bloom: AX model {} -> {} needs bone poses (rig={}); Bloom disabled for this object, AX body unaffected",key,binding.id,binding.rig);
            return AX_UNSUPPORTED;
        }
        if(binding.anim!=null){
            int[] firsts=skin(binding,axHash);
            if(firsts==null){axUnsupported++;return AX_UNSUPPORTED;}
            for(int i=0;i<binding.meshes.size();i++){
                if(count>=LIMIT){overflow++;break;}
                if(count==draws.size())draws.add(new Draw());
                Draw d=draws.get(count++);d.mesh=binding.meshes.get(i);d.first=firsts[i];d.matrix.set(matrix);d.phase=(instance*0.61803398875)%1.0*Math.PI*2;
            }
        }else push(binding.meshes,matrix,instance);
        axCaptured++;
        return AX_CAPTURED;
    }
    /**
     * Poses the binding's rig for the AX manager {@code axHash} and skins its meshes into this frame's vertex
     * stream. Objects sharing a hash share AX's pose, so they are skinned once per frame and drawn from the same
     * vertices. Returns the first skinned vertex of each mesh, or null when a budget is exhausted.
     */
    private int[] skin(AxBinding binding,int axHash){
        flushObservations();
        AxAnimation.Clock clock=clock(axHash,binding.anim.length,binding.anim.loop);
        if(clock==null)return null;
        if(clock.steppedFrame==frameId&&clock.owner==binding)return clock.firsts;
        int total=0;for(Mesh m:binding.meshes)total+=m.count;
        if(skinVertices+total>SKIN_LIMIT){skinOverflow++;return null;}
        clock.pose(binding.anim);
        clock.steppedFrame=frameId;clock.owner=binding;
        if(clock.firsts==null||clock.firsts.length!=binding.meshes.size())clock.firsts=new int[binding.meshes.size()];
        int need=(skinVertices+total)*20;
        if(skinData==null||skinData.capacity()<need){
            ByteBuffer grown=ByteBuffer.allocateDirect(Math.min(SKIN_LIMIT*20,Math.max(need,Integer.highestOneBit(Math.max(need-1,1))<<1))).order(ByteOrder.nativeOrder());
            if(skinData!=null){skinData.position(0).limit(skinVertices*20);grown.put(skinData);}
            skinData=grown;
        }
        skinData.limit(skinData.capacity()).position(skinVertices*20);
        Matrix4f[] pose=binding.anim.pose;
        for(int i=0;i<binding.meshes.size();i++){
            Mesh m=binding.meshes.get(i);clock.firsts[i]=skinVertices;
            float[] v=m.cpu;int[] r=m.ranges;
            for(int k=0;k<r.length;k+=3){
                Matrix4f b=pose[r[k]];
                float a00=b.m00(),a01=b.m01(),a02=b.m02(),a10=b.m10(),a11=b.m11(),a12=b.m12(),a20=b.m20(),a21=b.m21(),a22=b.m22(),a30=b.m30(),a31=b.m31(),a32=b.m32();
                for(int j=r[k+1]*5,end=(r[k+1]+r[k+2])*5;j<end;j+=5){
                    float x=v[j],y=v[j+1],z=v[j+2];
                    skinData.putFloat(a00*x+a10*y+a20*z+a30).putFloat(a01*x+a11*y+a21*z+a31).putFloat(a02*x+a12*y+a22*z+a32).putFloat(v[j+3]).putFloat(v[j+4]);
                }
            }
            skinVertices+=m.count;
        }
        skinned++;
        return clock.firsts;
    }
    /** Gets (or starts) the clock for AX manager {@code axHash} and steps it for this client frame; null past CLOCK_LIMIT. */
    private AxAnimation.Clock clock(int axHash,double length,boolean loop){
        AxAnimation.Clock clock=clocks.get(axHash);
        if(clock==null){if(clocks.size()>=CLOCK_LIMIT)return null;clock=new AxAnimation.Clock();clocks.put(axHash,clock);}
        clock.observe(clientFrame,org.lwjgl.glfw.GLFW.glfwGetTime()*60,MinecraftClient.getInstance().isPaused(),length,loop);
        return clock;
    }
    /** MinecraftClient.render HEAD. */
    public void frame(){flushObservations();clientFrame++;}
    /** True when AX model {@code key} has an animated Bloom binding, i.e. its AX renders must be observed. */
    public boolean animated(String key){return timing(key)!=null;}
    /**
     * Called at the head of every ItemRenderState.render that carries an AX geometry key, in any context
     * (world, GUI, hotbar, hand) and whether or not Bloom is enabled: AX starts and steps its manager on each
     * of these renders, so the mirrored clock must too. Unanimated keys cost one map lookup.
     */
    public long observeAx(String key,int axHash){
        AxAnimation.Timing t=timing(key);
        if(t==null||observationCount>=8192)return -1;
        if(observationCount==observations.size())observations.add(new Observation());
        int index=observationCount++;Observation o=observations.get(index);
        o.hash=axHash;o.frame=clientFrame;o.time=org.lwjgl.glfw.GLFW.glfwGetTime()*60;
        o.paused=MinecraftClient.getInstance().isPaused();o.timing=t;
        return ((long)observationGeneration<<32)|index;
    }
    /** The original RETURN ran: AX did not replace this render, so it must not start an idle clock. */
    public void discardObservation(long token){
        if(token<0||(int)(token>>>32)!=observationGeneration)return;
        int index=(int)token;if(index<observationCount)observations.get(index).timing=null;
    }
    private void flushObservations(){
        for(int i=0;i<observationCount;i++){
            Observation o=observations.get(i);if(o.timing==null)continue;
            AxAnimation.Clock c=clocks.get(o.hash);
            if(c==null&&clocks.size()<CLOCK_LIMIT){c=new AxAnimation.Clock();clocks.put(o.hash,c);}
            if(c!=null)c.observe(o.frame,o.time,o.paused,o.timing.length(),o.timing.loop());
            o.timing=null;
        }
        observationCount=0;observationGeneration++;
    }
    private AxAnimation.Timing timing(String key){
        if(timings.isEmpty())return null;
        AxAnimation.Timing hit=timings.get(key);if(hit!=null)return hit;
        Optional<AxAnimation.Timing> cached=timingLookups.get(key);if(cached!=null)return cached.orElse(null);
        String n=aliasKey(key);AxAnimation.Timing found=n==null?null:timings.get(n);
        if(timingLookups.size()<LOOKUP_CACHE)timingLookups.put(key,Optional.ofNullable(found));
        return found;
    }
    /** Vanilla item_model path; also the fallback for AX-tagged items that AX itself did not replace. */
    public void capture(Identifier id,Matrix4f matrix,int instance,boolean axFallbackPath){
        if(!active()||id==null)return;
        var found=res.itemMeshes.get(id);if(found==null)return;
        push(found,matrix,instance);if(axFallbackPath)axFallback++;
    }
    private boolean active(){return res!=null&&!iris&&failure.isEmpty()&&BloomClient.config.enabled;}
    private void push(List<Mesh> found,Matrix4f matrix,int instance){
        for(Mesh mesh:found){
            if(count>=LIMIT){overflow++;return;}
            if(count==draws.size())draws.add(new Draw());
            Draw d=draws.get(count++);d.mesh=mesh;d.matrix.set(matrix);d.phase=(instance*0.61803398875)%1.0*Math.PI*2;
        }
    }
    private static float number(JsonObject o,String key,float fallback,float min,float max)throws IOException{
        float v=o.has(key)?o.get(key).getAsFloat():fallback;
        if(!Float.isFinite(v)||v<min||v>max)throw new IOException("Invalid "+key+"="+v);
        return v;
    }
    private static String choice(JsonObject o,String key,String fallback,Set<String> allowed,Identifier id)throws IOException{
        String v=o.has(key)?o.get(key).getAsString():fallback;
        if(!allowed.contains(v))throw new IOException("Invalid "+key+"="+v+" "+id);
        return v;
    }
    private void load(Resources next,ResourceManager resources)throws Exception{
        // Sorted so the result never depends on resource traversal order.
        var found=new TreeMap<>(resources.findResources("bloom",id->id.getNamespace().equals("nightstar_bloom")&&id.getPath().endsWith(".json")));
        Map<Identifier,Identifier> owner=new HashMap<>();
        Map<String,Set<Identifier>> aliasClaims=new TreeMap<>();
        Set<Identifier> conflicted=new TreeSet<>();
        for(var entry:found.entrySet()){
            try(var reader=entry.getValue().getReader()){
                JsonObject root=JsonParser.parseReader(reader).getAsJsonObject();
                int version=root.has("version")?root.get("version").getAsInt():1;
                if(version<1||version>4)throw new IOException("Unsupported manifest version "+version);
                Map<String,AxAnimation.Rig> rigs=new HashMap<>();
                if(version>=4&&root.has("rigs"))for(var r:root.getAsJsonObject("rigs").entrySet())rigs.put(r.getKey(),AxAnimation.Rig.parse(r.getKey(),r.getValue().getAsJsonObject()));
                for(var value:root.getAsJsonArray("models")){
                    JsonObject obj=value.getAsJsonObject();Identifier id=Identifier.of(obj.get("id").getAsString());
                    Identifier previous=owner.putIfAbsent(id,entry.getKey());
                    if(previous!=null&&!previous.equals(entry.getKey())){
                        if(conflicted.add(id))next.conflicts.add("model "+id+" defined in both "+previous+" and "+entry.getKey()+"; dropped");
                        continue;
                    }
                    String texture=obj.get("texture").getAsString();
                    String preset=choice(obj,"preset","crystal",Set.of("crystal","core","rim","aura"),id);
                    String space=choice(obj,"space","item",SPACES,id),rig=choice(obj,"rig","static",RIGS,id);
                    if(space.equals("item")&&!rig.equals("static"))throw new IOException("rig="+rig+" requires space=ax "+id);
                    // An AX-space XYZUV mesh must say how it follows the body: rigid (whole-model pose), anim (idle
                    // animation replayed by Bloom) or bones (unsupported).
                    if(space.equals("ax")&&rig.equals("static"))throw new IOException("space=ax requires rig=rigid, anim or bones "+id);
                    AxAnimation.Rig animRig=null;
                    if(rig.equals("anim")){
                        String rigId=obj.has("animRig")?obj.get("animRig").getAsString():"";
                        animRig=rigs.get(rigId);if(animRig==null)throw new IOException("rig=anim references unknown animRig '"+rigId+"' "+id);
                    }
                    float[] color={1,1,1};
                    if(obj.has("color")){JsonArray a=obj.getAsJsonArray("color");if(a.size()!=3)throw new IOException("Invalid color "+id);for(int i=0;i<3;i++){color[i]=a.get(i).getAsFloat();if(!Float.isFinite(color[i])||color[i]<0||color[i]>4)throw new IOException("Invalid color "+id);}}
                    float strength=number(obj,"strength",1,0,4),period=number(obj,"period",3,0.25f,60),amplitude=number(obj,"amplitude",0.1f,0,0.5f);
                    String motion=choice(obj,"motion","inherit",Set.of("inherit","static","breath","flow"),id);
                    float[] flowStart=null,flowDir=null;float flowInvLength=0;
                    if(obj.has("flow")){JsonObject f=obj.getAsJsonObject("flow");flowStart=vec3(f,"start",id);flowDir=vec3(f,"dir",id);float norm=(float)Math.sqrt(flowDir[0]*flowDir[0]+flowDir[1]*flowDir[1]+flowDir[2]*flowDir[2]);if(norm<0.999f||norm>1.001f)throw new IOException("Invalid flow direction "+id);flowInvLength=number(f,"invLength",1,0.00001f,100000);}
                    if(obj.has("aliases")){
                        if(!space.equals("ax"))throw new IOException("aliases require space=ax "+id);
                        for(JsonElement a:obj.getAsJsonArray("aliases")){String key=aliasKey(a.getAsString());if(key==null)throw new IOException("Blank alias "+id);aliasClaims.computeIfAbsent(key,k->new TreeSet<>()).add(id);}
                    }
                    JsonArray v=obj.getAsJsonArray("vertices");
                    if(v.size()==0||v.size()%15!=0||v.size()>300000)throw new IOException("Invalid mesh "+id);
                    float[] raw=new float[v.size()];
                    for(int i=0;i<raw.length;i++){float f=v.get(i).getAsFloat();if(!Float.isFinite(f))throw new IOException("Non-finite vertex "+id);raw[i]=f;}
                    int vertexCount=raw.length/5;
                    if(!next.textures.containsKey(texture))next.textures.put(texture,Texture.read(resources,texture));
                    GpuBuffer buffer=null;float[] cpu=null;int[] ranges=null;
                    if(animRig!=null){
                        // Skinned every frame on the CPU; ranges must tile the vertex list exactly, in order.
                        JsonArray br=obj.getAsJsonArray("boneRanges");if(br==null||br.isEmpty())throw new IOException("rig=anim requires boneRanges "+id);
                        ranges=new int[br.size()*3];int next0=0;
                        for(int i=0;i<br.size();i++){
                            JsonArray t=br.get(i).getAsJsonArray();if(t.size()!=3)throw new IOException("Invalid boneRanges "+id);
                            int bone=t.get(0).getAsInt(),first=t.get(1).getAsInt(),n=t.get(2).getAsInt();
                            if(bone<0||bone>=animRig.bones.length||first!=next0||n<=0)throw new IOException("Invalid boneRanges "+id);
                            ranges[i*3]=bone;ranges[i*3+1]=first;ranges[i*3+2]=n;next0+=n;
                        }
                        if(next0!=vertexCount)throw new IOException("boneRanges cover "+next0+" of "+vertexCount+" vertices "+id);
                        cpu=raw;
                    }else{
                        ByteBuffer data=ByteBuffer.allocateDirect(raw.length*4).order(ByteOrder.nativeOrder());
                        for(float f:raw)data.putFloat(f);data.flip();
                        buffer=RenderSystem.getDevice().createBuffer(()->"Bloom mesh "+id,GpuBuffer.USAGE_VERTEX,data);
                    }
                    Mesh mesh=new Mesh(buffer,vertexCount,texture,preset,space,rig,color,strength,motion,period,amplitude,flowStart,flowDir,flowInvLength,cpu,ranges,animRig);
                    next.all.add(mesh);next.meshCount++;
                    (space.equals("ax")?next.axModels:next.itemMeshes).computeIfAbsent(id,k->new ArrayList<>()).add(mesh);
                }
            }catch(Exception e){throw new IOException("Manifest "+entry.getKey()+": "+e.getMessage(),e);}
        }
        for(Identifier id:conflicted){next.itemMeshes.remove(id);next.axModels.remove(id);}
        for(var claim:aliasClaims.entrySet()){
            Set<Identifier> ids=claim.getValue();
            if(ids.size()>1){next.conflicts.add("alias "+claim.getKey()+" claimed by "+ids+"; dropped");continue;}
            Identifier id=ids.iterator().next();var meshes=next.axModels.get(id);
            if(meshes==null)continue; // model itself was dropped as a conflict
            String rig=meshes.stream().map(Mesh::rig).filter(r->!r.equals("rigid")).findFirst().orElse("rigid");
            AxAnimation.Rig anim=null;
            if(rig.equals("anim")){
                anim=meshes.get(0).anim;
                for(Mesh m:meshes)if(m.anim!=anim)throw new IOException("AX model "+id+" mixes anim meshes with other rigs; all meshes must share one animRig");
            }
            AxBinding binding=new AxBinding(id,rig,!rig.equals("bones"),List.copyOf(meshes),anim);
            next.bindings.put(claim.getKey(),binding);next.bindings.put("arcartx_geo:"+claim.getKey(),binding);next.aliasCount++;
        }
    }
    /** AX resolves model ids case-insensitively; store the lowercased id without the geometry prefix. */
    private static String aliasKey(String raw){String n=AxCompat.normalize(raw);return n==null?null:n.toLowerCase(Locale.ROOT);}
    private static float[] vec3(JsonObject o,String key,Identifier id)throws IOException{if(!o.has(key)||!o.get(key).isJsonArray()||o.getAsJsonArray(key).size()!=3)throw new IOException("Invalid flow "+key+" "+id);float[] r=new float[3];for(int i=0;i<3;i++){r[i]=o.getAsJsonArray(key).get(i).getAsFloat();if(!Float.isFinite(r[i]))throw new IOException("Invalid flow "+key+" "+id);}return r;}
    private RenderPipeline pipeline(String shader,boolean mesh){
        var b=RenderPipeline.builder().withLocation(Identifier.of("nightstar_bloom",shader))
            .withVertexShader(Identifier.of("nightstar_bloom","core/"+(mesh?"mask":"screen")))
            .withFragmentShader(Identifier.of("nightstar_bloom","core/"+shader))
            .withUniform("Params",UniformType.UNIFORM_BUFFER).withSampler("Source")
            .withCull(false).withDepthWrite(false).withDepthTestFunction(mesh?DepthTestFunction.LEQUAL_DEPTH_TEST:DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(mesh?VertexFormats.POSITION_TEXTURE:VertexFormats.EMPTY,VertexFormat.DrawMode.TRIANGLES);
        if(!mesh)b.withSampler("Aux");
        if(shader.equals("combine"))b.withSampler("Core").withSampler("Narrow");
        return b.build();
    }
    private static int align(int bytes,int alignment){return (bytes+alignment-1)/alignment*alignment;}
    private void ensure(int width,int height){
        int n=switch(BloomClient.config.quality){case "low"->2;case "high"->4;default->3;};
        var device=RenderSystem.getDevice();
        if(postUniforms==null){
            int alignment=device.getUniformOffsetAlignment();
            stride=align(DRAW_BYTES,alignment);postStride=align(PARAM_BYTES,alignment);
            postUniforms=new MappableRingBuffer(()->"Bloom post uniforms",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,postStride*MAX_POST);
            postData=ByteBuffer.allocateDirect(postStride*MAX_POST).order(ByteOrder.nativeOrder());
            maskPipeline=pipeline("mask",true);blurPipeline=pipeline("blur",false);combinePipeline=pipeline("combine",false);
            linear=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.LINEAR,1,OptionalDouble.of(0));
            nearest=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.of(0));
        }
        if(count>capacity){
            if(drawUniforms!=null)drawUniforms.close();capacity=Math.min(LIMIT,Math.max(64,Integer.highestOneBit(count-1)<<1));
            drawUniforms=new MappableRingBuffer(()->"Bloom draw uniforms",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,stride*capacity*3);
            drawData=ByteBuffer.allocateDirect(stride*capacity*3).order(ByteOrder.nativeOrder());
        }
        if(w==width&&h==height&&levels==n)return;
        closeTargets();w=width;h=height;levels=n;
        for(int i=0;i<3;i++)masks[i]=new Target(w,h);
        scene=new Target(w,h);narrow=new Target(Math.max(1,w/2),Math.max(1,h/2));temp=new Target(narrow.w,narrow.h);
        for(int i=0;i<n;i++){int x=Math.max(1,w>>(i+1)),y=Math.max(1,h>>(i+1));down.add(new Target(x,y));up.add(new Target(x,y));}
    }
    private float layerWeight(Mesh m,int layer){
        return switch(m.preset){
            case "core" -> layer==0?1:layer==1?.25f:.12f;
            case "rim" -> layer==0?.3f:layer==1?1:.3f;
            case "aura" -> layer==0?.12f:layer==1?.3f:1;
            default -> 1;
        };
    }
    private void prepareDraws(CommandEncoder e){
        if(count==0)return;
        drawData.clear();
        for(int layer=0;layer<3;layer++)for(int i=0;i<count;i++){
            Draw d=draws.get(i);Mesh m=d.mesh;int offset=(layer*capacity+i)*stride;
            mvp.set(vp).mul(d.matrix).get(offset,drawData);drawData.position(offset+64);
            String motion=m.motion.equals("inherit")?BloomClient.config.motion:m.motion;
            float pulse=motion.equals("breath")?1+m.amplitude*(float)Math.sin(time*2*Math.PI/m.period+d.phase):1;
            boolean flow=motion.equals("flow");
            // One synchronized wave per item_display model, so split meshes stay aligned.
            float flowPhase=flow?(float)((time*BloomClient.config.flowSpeed)%1.0):0f;
            drawData.putFloat(layer).putFloat(flowPhase).putFloat(flow?1f:0f).putFloat(0);
            drawData.putFloat(m.color[0]).putFloat(m.color[1]).putFloat(m.color[2]).putFloat(m.strength*pulse*layerWeight(m,layer));
            if(m.flowStart!=null&&m.flowDir!=null){drawData.putFloat(m.flowStart[0]).putFloat(m.flowStart[1]).putFloat(m.flowStart[2]).putFloat(1);drawData.putFloat(m.flowDir[0]).putFloat(m.flowDir[1]).putFloat(m.flowDir[2]).putFloat(m.flowInvLength);}else{drawData.putFloat(0).putFloat(0).putFloat(0).putFloat(0);drawData.putFloat(0).putFloat(0).putFloat(0).putFloat(0);}
        }
        drawData.position(0);drawData.limit(stride*capacity*3);
        try(var mapped=e.mapBuffer(drawUniforms.getBlocking(),false,true)){mapped.data().put(drawData);}
    }
    /** Records one post pass; parameters are uploaded together once all passes of the frame are planned. */
    private int plan(Target src,Target aux,Target out,float x,float y,float radius,float mode){
        if(postCount>=MAX_POST)throw new IllegalStateException("Bloom post pass budget exceeded");
        int i=postCount++;postSrc[i]=src;postAux[i]=aux;postOut[i]=out;
        postData.position(i*postStride);identity.get(i*postStride,postData);postData.position(i*postStride+64);
        var c=BloomClient.config;
        postData.putFloat(x).putFloat(y).putFloat(radius).putFloat(mode);
        postData.putFloat(c.color[0]).putFloat(c.color[1]).putFloat(c.color[2]).putFloat(c.strength);
        return i;
    }
    private void blur(Target src,Target aux,Target out,float radius,float mode){plan(src,aux,out,1f/src.w,1f/src.h,radius,mode);}
    public void render(){
        frames++;passCount=drawCount=0;
        var cfg=BloomClient.config;
        if(!cfg.enabled){if(postUniforms!=null||res!=null)close();return;}
        if(iris||!failure.isEmpty()||res==null||(count==0&&!cfg.debug.equals("mask")&&!cfg.debug.equals("overlay")))return;
        var target=MinecraftClient.getInstance().getFramebuffer();if(target.getDepthAttachmentView()==null)return;
        try{
            ensure(target.textureWidth,target.textureHeight);var e=RenderSystem.getDevice().createCommandEncoder();prepareDraws(e);
            GpuBuffer skin=null;
            if(count>0&&skinVertices>0){
                int bytes=skinVertices*20;
                if(skinRing==null||skinRing.size()<bytes){
                    if(skinRing!=null)skinRing.close();
                    int size=Math.min(SKIN_LIMIT*20,Math.max(bytes,Integer.highestOneBit(Math.max(bytes-1,1))<<1));
                    skinRing=new MappableRingBuffer(()->"Bloom skinned vertices",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_MAP_WRITE,size);
                }
                skin=skinRing.getBlocking();
                skinData.position(0).limit(bytes);
                try(var mapped=e.mapBuffer(skin,false,true)){mapped.data().put(skinData);}
            }
            for(int layer=0;layer<3;layer++){
                e.clearColorTexture(masks[layer].tex,0);
                if(count==0)continue;
                try(var pass=e.createRenderPass(()->"Bloom geometry mask",masks[layer].view,OptionalInt.empty(),target.getDepthAttachmentView(),OptionalDouble.empty())){
                    pass.setPipeline(maskPipeline);
                    for(int i=0;i<count;i++){
                        Draw d=draws.get(i);pass.setUniform("Params",drawUniforms.getBlocking().slice((long)(layer*capacity+i)*stride,DRAW_BYTES));
                        pass.bindTexture("Source",res.textures.get(d.mesh.texture).view,nearest);
                        if(d.mesh.buffer!=null){pass.setVertexBuffer(0,d.mesh.buffer);pass.draw(0,d.mesh.count);}
                        else{pass.setVertexBuffer(0,skin);pass.draw(d.first,d.mesh.count);}
                        drawCount++;
                    }
                }passCount++;
            }
            if(count>0)drawUniforms.rotate();
            if(skin!=null)skinRing.rotate();
            postCount=0;postData.clear();
            Target wide=masks[2];
            if(cfg.radius>0&&cfg.debug.equals("off")){
                blur(masks[1],masks[1],narrow,1,0);
                blur(narrow,narrow,temp,cfg.radius*.85f,2);
                blur(temp,temp,narrow,cfg.radius*.85f,3);
                Target input=masks[2];
                // Fixed total variance across quality levels; lower tiers use a denser kernel.
                float levelRadius=cfg.radius*(float)Math.sqrt(84.0/(Math.pow(4,levels+1)-4));
                for(int i=0;i<levels;i++){
                    Target output=down.get(i),tmp=up.get(i);
                    blur(input,input,output,1,i==0?4:0); // bounded one-pixel dilation only in aura
                    blur(output,output,tmp,levelRadius,2);
                    blur(tmp,tmp,output,levelRadius,3);input=output;
                }
                for(int i=levels-2;i>=0;i--){Target output=up.get(i);blur(input,down.get(i),output,cfg.radius,1);input=output;}
                wide=input;
            }
            int mode=cfg.debug.equals("mask")?1:cfg.debug.equals("scene")?2:cfg.debug.equals("overlay")?3:0;
            int combine=plan(null,null,null,cfg.core*(cfg.preset.equals("soft")?.8f:cfg.preset.equals("restrained")?.8f:1f),
                cfg.halo*(cfg.preset.equals("soft")?.6f:cfg.preset.equals("restrained")?.45f:1.25f),
                cfg.radius==0?0:cfg.halo*(cfg.preset.equals("soft")?1.6f:cfg.preset.equals("restrained")?.18f:.45f),mode);
            postData.position(0);postData.limit(postStride*postCount);
            GpuBuffer post=postUniforms.getBlocking();
            try(var mapped=e.mapBuffer(post,false,true)){mapped.data().put(postData);}
            for(int i=0;i<combine;i++){
                int slot=i;
                try(var pass=e.createRenderPass(()->"Bloom separable convolution",postOut[slot].view,OptionalInt.empty())){
                    pass.setPipeline(blurPipeline);pass.setUniform("Params",post.slice((long)slot*postStride,PARAM_BYTES));
                    pass.bindTexture("Source",postSrc[slot].view,linear);pass.bindTexture("Aux",postAux[slot].view,linear);pass.draw(0,3);
                }passCount++;
            }
            e.copyTextureToTexture(target.getColorAttachment(),scene.tex,0,0,0,0,0,w,h);
            try(var pass=e.createRenderPass(()->"Bloom composite before HUD",target.getColorAttachmentView(),OptionalInt.empty())){
                pass.setPipeline(combinePipeline);pass.setUniform("Params",post.slice((long)combine*postStride,PARAM_BYTES));
                pass.bindTexture("Source",wide.view,linear);pass.bindTexture("Aux",scene.view,nearest);
                pass.bindTexture("Core",masks[0].view,nearest);pass.bindTexture("Narrow",(cfg.radius==0?masks[1]:narrow).view,linear);pass.draw(0,3);
            }passCount++;
            postUniforms.rotate();
            Arrays.fill(postSrc,null);Arrays.fill(postAux,null);Arrays.fill(postOut,null);
        }catch(Exception|LinkageError ex){fail(ex);}
    }
    private void fail(Throwable e){failure=e.toString();reloadState="failed";BloomClient.LOG.error("Bloom disabled after render error; all Bloom GPU resources released until the next resource reload",e);close();}
    public String status(){
        long bytes=scene==null?0:16L*w*h+8L*narrow.w*narrow.h;
        for(Target t:down)bytes+=4L*t.w*t.h;for(Target t:up)bytes+=4L*t.w*t.h;
        var c=BloomClient.config;Resources r=res;
        String conflicts=r==null||r.conflicts.isEmpty()?"0":r.conflicts.size()+" "+r.conflicts.subList(0,Math.min(3,r.conflicts.size()));
        return "[星辉] v0.3.0 enabled="+c.enabled+" preset="+c.preset+" motion="+c.motion+" core="+c.core+" halo="+c.halo+" quality="+c.quality
            +" generation="+generation+" meshes="+(r==null?0:r.meshCount)+" textures="+(r==null?0:r.textures.size())+" visible="+count+" draws="+drawCount+" passes="+passCount
            +" targets="+(scene==null?0:6+2*levels)+" targetBytes="+bytes+" overflow="+overflow+" reload="+reloadState
            +" iris="+iris+" ax="+AxCompat.status()+" axBindings="+(r==null?0:r.axModels.size())+" aliases="+(r==null?0:r.aliasCount)
            +" axCaptured="+axCaptured+" axFallback="+axFallback+" axUnmapped="+axUnmapped+" axUnsupported="+axUnsupported
            +" axAnimated="+skinned+" skinnedVertices="+skinVertices+" skinOverflow="+skinOverflow+" clocks="+clocks.size()
            +" conflicts="+conflicts+" loadError="+loadError+" failure="+failure
            +(diagnostics ? "|diag seen="+diagSeen+" anc="+diagAncestor+" head="+diagHead+" ret="+diagRet
            +" noState="+diagNoState+" vanilla="+diagVanilla+" noGeo="+diagNoGeo
            +"|gui head="+diagGuiHead+" ret="+diagGuiRet+" stack="+diagGuiStack+" geo="+diagGuiGeo+" geoRet="+diagGuiGeoRet
            +" key="+diagGuiKey+" other="+diagOtherHead+" frames="+frames
            +"|keycalls all="+diagKeyAll+" str="+diagKeyStr+" other="+diagKeyOther+" geo="+diagKeyGeo
            +" geoOther="+diagKeyGeoOther+" guiAll="+diagKeyGuiAll
            +" odd="+(diagKeyOdd==null?"-":diagKeyOdd)
            +" gkey="+(diagGeoKeySample==null?"-":diagGeoKeySample)
            +"|gui modelNoKey="+diagGuiModelNoKey+" mkeySample="+(diagGuiModelSample==null?"-":diagGuiModelSample)
            +"|path clear="+diagClearUpdate+"/"+diagClearUpdateGui+"/"+diagClearUpdateModel
            +" update="+diagUpdate+"/"+diagUpdateGui+"/"+diagUpdateModel
            +"|keystate gui="+(diagStateGuiClass==null?"-":diagStateGuiClass)+" other="+(diagStateOtherClass==null?"-":diagStateOtherClass)
            +"|keyed all="+diagKeyKeyed+" gui="+diagKeyKeyedGui+" geo="+diagKeyKeyedGeo+" geoGui="+diagKeyKeyedGeoGui
            +" sample="+(diagKeyedSample==null?"-":diagKeyedSample) : " diagnostics=off");
    }
    private void closeTargets(){for(int i=0;i<3;i++){if(masks[i]!=null)masks[i].close();masks[i]=null;}if(scene!=null)scene.close();if(narrow!=null)narrow.close();if(temp!=null)temp.close();scene=narrow=temp=null;down.forEach(Target::close);up.forEach(Target::close);down.clear();up.clear();w=h=levels=0;}
    /** Releases every GPU object Bloom owns. The next enabled frame reloads the manifests. */
    public void close(){
        releaseSkinBindings();
        closeTargets();if(res!=null)res.close();res=null;
        if(postUniforms!=null)postUniforms.close();if(drawUniforms!=null)drawUniforms.close();if(linear!=null)linear.close();if(nearest!=null)nearest.close();
        if(skinRing!=null)skinRing.close();skinRing=null;skinData=null;skinVertices=0;
        postUniforms=drawUniforms=null;linear=nearest=null;drawData=postData=null;capacity=0;count=0;draws.clear();loadAttempted=false;
        Arrays.fill(postSrc,null);Arrays.fill(postAux,null);Arrays.fill(postOut,null);
    }
    private static final class Resources implements AutoCloseable{
        final Map<Identifier,List<Mesh>> itemMeshes=new HashMap<>(),axModels=new HashMap<>();
        final Map<String,AxBinding> bindings=new HashMap<>();
        final Map<String,Optional<AxBinding>> lookups=new HashMap<>();
        final Map<String,Texture> textures=new HashMap<>();
        final List<Mesh> all=new ArrayList<>();
        final List<String> conflicts=new ArrayList<>();
        int meshCount,aliasCount;
        /** Exact hit first; other spellings are normalised once and cached, so steady-state frames do not allocate. */
        AxBinding resolve(String key){
            AxBinding hit=bindings.get(key);if(hit!=null)return hit;
            Optional<AxBinding> cached=lookups.get(key);if(cached!=null)return cached.orElse(null);
            String n=aliasKey(key);AxBinding found=n==null?null:bindings.get(n);
            if(lookups.size()<LOOKUP_CACHE)lookups.put(key,Optional.ofNullable(found));
            return found;
        }
        public void close(){all.forEach(m->{if(m.buffer!=null)m.buffer.close();});all.clear();textures.values().forEach(Texture::close);textures.clear();itemMeshes.clear();axModels.clear();bindings.clear();lookups.clear();}
    }
    /** rigid = Bloom can draw it (rigid or anim); anim != null = meshes are skinned per frame. */
    private record AxBinding(Identifier id,String rig,boolean rigid,List<Mesh> meshes,AxAnimation.Rig anim){}
    /** buffer is null for anim meshes: cpu holds XYZUV, ranges holds (bone, firstVertex, vertexCount) triples. */
    private record Mesh(GpuBuffer buffer,int count,String texture,String preset,String space,String rig,float[] color,float strength,String motion,float period,float amplitude,float[] flowStart,float[] flowDir,float flowInvLength,float[] cpu,int[] ranges,AxAnimation.Rig anim){}
    /** first = first vertex in this frame's skin stream (anim meshes only). */
    private static class Draw{Mesh mesh;int first;double phase;final Matrix4f matrix=new Matrix4f();}
    private record Texture(GpuTexture tex,GpuTextureView view){
        static Texture read(ResourceManager resources,String texture)throws IOException{
            try(var input=resources.getResourceOrThrow(Identifier.of(texture)).getInputStream();var img=NativeImage.read(input)){
                var device=RenderSystem.getDevice();
                var tex=device.createTexture(()->"Bloom source "+texture,GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,TextureFormat.RGBA8,img.getWidth(),img.getHeight(),1,1);
                try{device.createCommandEncoder().writeToTexture(tex,img);return new Texture(tex,device.createTextureView(tex));}catch(RuntimeException e){tex.close();throw e;}
            }
        }
        void close(){view.close();tex.close();}
    }
    private static class Target{
        final int w,h;final GpuTexture tex;final GpuTextureView view;
        Target(int w,int h){this.w=w;this.h=h;var d=RenderSystem.getDevice();tex=d.createTexture(()->"Bloom target",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_RENDER_ATTACHMENT,TextureFormat.RGBA8,w,h,1,1);view=d.createTextureView(tex);}
        void close(){view.close();tex.close();}
    }
}
