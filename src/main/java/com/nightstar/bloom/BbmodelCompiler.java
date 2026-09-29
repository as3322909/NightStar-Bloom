package com.nightstar.bloom;

import com.google.gson.*;
import org.joml.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** First-party BB cube compiler. Runs only at reload; AX remains the sole pose/animation authority. */
final class BbmodelCompiler {
    static final int MAX_BYTES=32*1024*1024, MAX_VERTICES=262144;
    record Compiled(JsonObject manifest, Map<String,byte[]> textures, int vertices) {}
    interface TextureReader { byte[] read(String path)throws IOException; }
    private record Cube(JsonObject data, String bone, String preset, JsonObject settings, String path, Matrix4f parent) {}
    private record Bucket(int texture,String preset,JsonObject settings) {}
    private final String source,alias,id;
    private final JsonObject root;
    private final Map<String,JsonObject> elements=new LinkedHashMap<>(),groups=new HashMap<>();
    private final Set<String> seen=new HashSet<>(),groupIds=new HashSet<>(),allNames=new HashSet<>(),duplicateNames=new HashSet<>();
    private final List<Cube> cubes=new ArrayList<>();
    private final Map<String,Vector3f> markers=new HashMap<>();
    private final Map<String,Integer> bones=new LinkedHashMap<>();
    private int vertexCount;
    private BbmodelCompiler(String source,String alias,JsonObject root){this.source=source;this.alias=alias;this.root=root;id="nightstar_bloom:auto/"+hash(alias.toLowerCase(Locale.ROOT));}
    static Compiled compile(String source,String alias,JsonObject root,TextureReader reader)throws IOException{
        try{return new BbmodelCompiler(source,alias,root).compile(reader);}
        catch(Exception e){throw new IOException(source+": "+e.getMessage(),e);}
    }
    private Compiled compile(TextureReader reader)throws IOException{
        for(JsonElement value:array(root,"elements")){
            JsonObject e=value.getAsJsonObject();String uuid=required(e,"uuid");
            if(elements.putIfAbsent(uuid,e)!=null)throw error("duplicate element UUID="+uuid);
        }
        for(JsonElement value:array(root,"groups")){
            JsonObject g=value.getAsJsonObject();String uuid=required(g,"uuid");
            if(groups.putIfAbsent(uuid,g)!=null)throw error("duplicate group UUID="+uuid);
        }
        for(JsonElement node:array(root,"outliner"))walk(node,new Matrix4f(),null,new JsonObject(),null,"root",0);
        JsonObject manifest=new JsonObject();manifest.addProperty("version",4);JsonArray models=new JsonArray();manifest.add("models",models);
        if(cubes.isEmpty())return new Compiled(manifest,Map.of(),0);
        if(bones.size()>4096)throw error("more than 4096 glow bones");
        JsonArray boneList=new JsonArray();
        for(String name:bones.keySet()){
            if(duplicateNames.contains(name))throw error("ambiguous glow bone name="+name);
            JsonObject bone=new JsonObject();bone.addProperty("name",name);bone.addProperty("parent",-1);boneList.add(bone);
        }
        JsonObject rig=new JsonObject();rig.add("bones",boneList);JsonObject rigs=new JsonObject();rigs.add(id,rig);manifest.add("rigs",rigs);
        JsonArray ts=array(root,"textures");
        Map<Bucket,Map<Integer,List<Float>>> buckets=new LinkedHashMap<>();
        for(Cube cube:cubes)emit(cube,ts,buckets);
        Map<String,byte[]> images=new LinkedHashMap<>();
        for(var entry:buckets.entrySet()){
            Bucket bucket=entry.getKey();String texture=id+"/"+bucket.texture+".png";
            if(!images.containsKey(texture))images.put(texture,texture(ts.get(bucket.texture).getAsJsonObject(),reader));
            JsonObject model=bucket.settings.deepCopy();
            // Static models also have context-dependent display and attachment transforms.
            // Keep bone-local geometry and use the actual AX draw pose in every context.
            model.addProperty("id",id);model.addProperty("space","ax");model.addProperty("rig","anim");model.addProperty("animRig",id);
            model.addProperty("texture",texture);model.addProperty("preset",bucket.preset);model.addProperty("source",source);
            JsonArray aliases=new JsonArray();aliases.add(alias);model.add("aliases",aliases);
            JsonArray vertices=new JsonArray(),ranges=new JsonArray();
            for(var range:entry.getValue().entrySet()){
                JsonArray r=new JsonArray();r.add(range.getKey());r.add(vertices.size()/5);r.add(range.getValue().size()/5);ranges.add(r);
                range.getValue().forEach(vertices::add);
            }
            model.add("vertices",vertices);model.add("boneRanges",ranges);
            if(!markers.isEmpty())model.add("flow",flow());
            models.add(model);
        }
        return new Compiled(manifest,Map.copyOf(images),vertexCount);
    }
    private void walk(JsonElement node,Matrix4f parent,String preset,JsonObject settings,String bone,String path,int depth)throws IOException{
        if(depth>128)throw error("group hierarchy exceeds 128 levels at "+path);
        if(node.isJsonPrimitive()){
            String uuid=node.getAsString();
            if(groups.containsKey(uuid)){walk(groups.get(uuid),parent,preset,settings,bone,path,depth+1);return;}
            JsonObject e=elements.get(uuid);if(e==null)throw error("unknown element UUID="+uuid+" group="+path);
            if(!seen.add(uuid))throw error("repeated element UUID="+uuid+" group="+path);
            if(preset==null||!visible(e))return;
            if(!string(e,"type","cube").equals("cube"))throw error("unsupported geometry UUID="+uuid+" group="+path);
            if(bone==null||bone.isBlank())throw error("unnamed glow group UUID="+uuid+" group="+path);
            bones.computeIfAbsent(bone,k->bones.size());cubes.add(new Cube(e,bone,preset,settings,path,new Matrix4f(parent)));return;
        }
        JsonObject g=node.getAsJsonObject().deepCopy();String uuid=string(g,"uuid",path+"/"+depth);
        JsonObject definition=groups.get(uuid);
        if(definition!=null){JsonObject merged=definition.deepCopy();for(var e:g.entrySet())merged.add(e.getKey(),e.getValue());g=merged;}
        if(!groupIds.add(uuid))throw error("repeated/cyclic group UUID="+uuid+" group="+path);
        String name=string(g,"name","");path+="/"+name;
        if(!allNames.add(name))duplicateNames.add(name);
        String marker=(name.endsWith("__flow_start")||name.endsWith("__flow_1"))?"start":(name.endsWith("__flow_end")||name.endsWith("__flow_2"))?"end":null;
        if(marker!=null){
            if(!array(g,"children").isEmpty())throw error("flow marker must be empty group="+path);
            if(markers.putIfAbsent(marker,parent.transformPosition(vector(g,"origin",0),new Vector3f()))!=null)throw error("duplicate flow "+marker+" group="+path);
            return;
        }
        String explicit=preset(name);if(explicit!=null)preset=explicit;
        JsonObject inherited=settings.deepCopy();
        if(g.has("bloom"))for(var e:g.getAsJsonObject("bloom").entrySet())inherited.add(e.getKey(),e.getValue());
        if(!visible(g))return;
        Vector3f origin=vector(g,"origin",0),rotation=vector(g,"rotation",0);
        Matrix4f transform=new Matrix4f(parent).mul(rotation(origin,rotation));
        for(JsonElement child:array(g,"children"))walk(child,transform,preset,inherited,name,path,depth+1);
    }
    private void emit(Cube cube,JsonArray textures,Map<Bucket,Map<Integer,List<Float>>> buckets)throws IOException{
        JsonObject e=cube.data;String where=" UUID="+required(e,"uuid")+" group="+cube.path;
        if(!e.has("from")||!e.has("to"))throw error("missing cube bounds"+where);
        Vector3f from=vector(e,"from",0),to=vector(e,"to",0),origin=vector(e,"origin",0);
        float inflate=number(e,"inflate",0);Vector3f center=new Vector3f(from).add(to).mul(.5f),half=new Vector3f(to).sub(from).absolute().mul(.5f).add(inflate,inflate,inflate);
        if(half.x<0||half.y<0||half.z<0)throw error("negative cube extent"+where);
        Matrix4f transform=rotation(origin,vector(e,"rotation",0));
        float x=center.x-half.x,y=center.y-half.y,z=center.z-half.z,X=center.x+half.x,Y=center.y+half.y,Z=center.z+half.z;
        float[][][] corners={{{X,Y,z},{X,y,z},{x,y,z},{x,Y,z}},{{x,Y,Z},{x,y,Z},{X,y,Z},{X,Y,Z}},{{x,Y,z},{x,y,z},{x,y,Z},{x,Y,Z}},{{X,Y,Z},{X,y,Z},{X,y,z},{X,Y,z}},{{x,Y,z},{x,Y,Z},{X,Y,Z},{X,Y,z}},{{x,y,Z},{x,y,z},{X,y,z},{X,y,Z}}};
        String[] sides={"north","south","west","east","up","down"};JsonObject faces=object(e,"faces");
        for(var f:faces.entrySet())if(!List.of(sides).contains(f.getKey()))throw error("unknown face "+f.getKey()+where);
        for(int side=0;side<sides.length;side++){
            if(!faces.has(sides[side]))continue;JsonObject face=faces.getAsJsonObject(sides[side]);
            if(!face.has("texture")||face.get("texture").isJsonNull())continue;
            int ti=textureIndex(face.get("texture"),textures);if(ti<0||ti>=textures.size())throw error("missing texture "+face.get("texture")+where);
            JsonObject tex=textures.get(ti).getAsJsonObject(),resolution=object(root,"resolution");
            float tw=number(tex,"uv_width",number(resolution,"width",16)),th=number(tex,"uv_height",number(resolution,"height",16));
            if(tw<=0||th<=0)throw error("invalid texture dimensions"+where);
            JsonArray uv=face.getAsJsonArray("uv");if(uv==null||uv.size()!=4)throw error("invalid face UV"+where);
            float u=finite(uv.get(0))/tw,v=finite(uv.get(1))/th,U=finite(uv.get(2))/tw,V=finite(uv.get(3))/th;
            float rot=number(face,"rotation",0);if(rot!=0&&rot!=90&&rot!=180&&rot!=270)throw error("invalid UV rotation"+where);
            float[][] uvv={{u,v},{u,V},{U,V},{U,v}};
            Bucket key=new Bucket(ti,cube.preset,cube.settings);
            List<Float> output=buckets.computeIfAbsent(key,k->new TreeMap<>()).computeIfAbsent(bones.get(cube.bone),k->new ArrayList<>());
            for(int j:new int[]{0,1,2,0,2,3}){
                Vector3f p=transform.transformPosition(new Vector3f(corners[side][j])).div(16);
                if(!p.isFinite())throw error("invalid transformed vertex"+where);
                output.add(p.x);output.add(p.y);output.add(p.z);float[] st=uvv[(j+(int)rot/90)%4];output.add(st[0]);output.add(st[1]);
                if(++vertexCount>MAX_VERTICES)throw error("glow vertex budget exceeded"+where);
            }
        }
    }
    private JsonObject flow()throws IOException{
        if(markers.size()!=2)throw error("both __flow_start and __flow_end are required");
        Matrix4f base=base();
        Vector3f start=base.transformPosition(new Vector3f(markers.get("start")).div(16));
        Vector3f delta=base.transformPosition(new Vector3f(markers.get("end")).div(16)).sub(start);float length=delta.length();
        if(!Float.isFinite(length)||length<.00001f)throw error("coincident flow markers");
        JsonObject f=new JsonObject();f.add("start",json(start));f.add("dir",json(delta.div(length)));f.addProperty("invLength",1/length);return f;
    }
    private Matrix4f base()throws IOException{
        Matrix4f base=new Matrix4f();JsonObject fixed=object(object(root,"display"),"fixed");
        if(object(root,"display").has("fixed")){
            Vector3f t=vector(fixed,"translation",1).div(16),r=vector(fixed,"rotation",1),s=vector(fixed,"scale",1);
            t.max(new Vector3f(-5)).min(new Vector3f(5));s.max(new Vector3f(-4)).min(new Vector3f(4));
            base.translate(t).rotateXYZ(rad(r.x),rad(r.y),rad(r.z)).scale(s).translate(0,-.5f,0);
        }
        return base.translate(0,.01f,0);
    }
    private static Matrix4f rotation(Vector3f origin,Vector3f r){return new Matrix4f().translate(origin).rotateZYX(rad(r.z),rad(r.y),rad(r.x)).translate(-origin.x,-origin.y,-origin.z);}
    private static float rad(float v){return (float)java.lang.Math.toRadians(v);}
    static String preset(String name){if(name.endsWith("__bloom"))return "crystal";for(String p:List.of("core","rim","aura"))if(name.endsWith("__bloom_"+p))return p;return null;}
    private static int textureIndex(JsonElement ref,JsonArray textures){try{return Integer.parseInt(ref.getAsString().replaceFirst("^#",""));}catch(NumberFormatException e){for(int i=0;i<textures.size();i++)if(ref.getAsString().equals(string(textures.get(i).getAsJsonObject(),"uuid","")))return i;return -1;}}
    private static byte[] texture(JsonObject t,TextureReader reader)throws IOException{
        String source=string(t,"source","");byte[] data;
        if(source.startsWith("data:image/png;base64,")){
            if(source.length()>MAX_BYTES*4L/3+128)throw new IOException("embedded texture exceeds limit");
            try{data=Base64.getDecoder().decode(source.substring(source.indexOf(',')+1));}catch(IllegalArgumentException e){throw new IOException("invalid embedded PNG",e);}
        }else{
            String path=string(t,"relative_path",string(t,"path",string(t,"name","")));data=reader.read(path);
        }
        if(data==null||data.length<24||data.length>MAX_BYTES||data[0]!=(byte)137||data[1]!=80||data[2]!=78||data[3]!=71)throw new IOException("missing or invalid PNG texture");
        var b=java.nio.ByteBuffer.wrap(data);int w=b.getInt(16),h=b.getInt(20);
        if(w<1||h<1||w>8192||h>8192||(long)w*h>16777216)throw new IOException("texture pixel budget exceeded");
        return data;
    }
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private IOException error(String message){return new IOException(message);}
    private static boolean visible(JsonObject o){return (!o.has("visibility")||o.get("visibility").getAsBoolean())&&(!o.has("export")||o.get("export").getAsBoolean());}
    private static JsonObject object(JsonObject o,String name){return o.has(name)?o.getAsJsonObject(name):new JsonObject();}
    private static String required(JsonObject o,String key)throws IOException{if(!o.has(key))throw new IOException("missing "+key);return o.get(key).getAsString();}
    static String string(JsonObject o,String key,String fallback){return o.has(key)?o.get(key).getAsString():fallback;}
    static JsonArray array(JsonObject o,String key){return o.has(key)?o.getAsJsonArray(key):new JsonArray();}
    private static float finite(JsonElement e)throws IOException{float f=e.getAsFloat();if(!Float.isFinite(f))throw new IOException("non-finite number");return f;}
    private static float number(JsonObject o,String key,float fallback)throws IOException{return o.has(key)?finite(o.get(key)):fallback;}
    private static Vector3f vector(JsonObject o,String key,float fallback)throws IOException{if(!o.has(key))return new Vector3f(fallback);JsonArray a=o.getAsJsonArray(key);if(a.size()!=3)throw new IOException("invalid vector "+key);return new Vector3f(finite(a.get(0)),finite(a.get(1)),finite(a.get(2)));}
    private static JsonArray json(Vector3f v){JsonArray a=new JsonArray();a.add(v.x);a.add(v.y);a.add(v.z);return a;}
}
