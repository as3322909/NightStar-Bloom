package com.nightstar.bloom;

import com.google.gson.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Generated first-party fixtures; no AX weapon or texture is included. */
public final class BbmodelCompilerTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static JsonObject fixture()throws Exception{
        JsonObject root=JsonParser.parseString("""
            {"animations":[{"name":"idle"}],"resolution":{"width":32,"height":64},"textures":[],"elements":[
              {"uuid":"a","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,32,64],"texture":0,"rotation":90}}},
              {"uuid":"b","from":[16,0,0],"to":[32,16,16],"faces":{"south":{"uv":[0,0,32,64],"texture":1}}},
              {"uuid":"plain","from":[0,0,0],"to":[2,2,2]}],
             "groups":[{"uuid":"g","name":"crystal__bloom","origin":[16,0,0],"rotation":[0,0,90]}],
             "outliner":[{"uuid":"g","children":["a",{"uuid":"child","name":"facet__bloom_rim","children":["b"]}]},"plain"]}
            """).getAsJsonObject();
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB),"png",output);
        for(int i=0;i<2;i++){JsonObject t=new JsonObject();t.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray()));root.getAsJsonArray("textures").add(t);}
        return root;
    }
    private static BbmodelCompiler.Compiled compile(JsonObject root)throws IOException{return BbmodelCompiler.compile("fixture.bbmodel","test/crystal",root,path->{throw new IOException("unexpected external texture");});}
    private static void rejected(JsonObject root,String why)throws Exception{try{compile(root);throw new AssertionError("accepted "+why);}catch(IOException expected){check(expected.getMessage().contains("fixture.bbmodel"),"error source");}}
    public static void main(String[] args)throws Exception{
        var c=compile(fixture());JsonArray models=c.manifest().getAsJsonArray("models");
        check(c.vertices()==12&&models.size()==2&&c.textures().size()==2,"texture/preset splitting");
        JsonObject first=models.get(0).getAsJsonObject();check(first.get("rig").getAsString().equals("anim"),"actual draw poses required even for static groups");
        JsonArray vertices=first.getAsJsonArray("vertices");
        check(vertices.get(0).getAsFloat()==1&&vertices.get(1).getAsFloat()==1,"group rotation must be applied by captured pose exactly once");
        check(vertices.get(3).getAsFloat()==0&&vertices.get(4).getAsFloat()==1,"UV dimension and face rotation");
        check(models.get(1).getAsJsonObject().get("preset").getAsString().equals("rim"),"child preset override");
        JsonObject r=fixture();r.getAsJsonArray("outliner").get(0).getAsJsonObject().getAsJsonArray("children").add("a");rejected(r,"duplicate cube");
        r=fixture();r.getAsJsonArray("elements").get(0).getAsJsonObject().addProperty("type","mesh");rejected(r,"unsupported mesh");
        r=fixture();r.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north").addProperty("texture",7);rejected(r,"missing texture");
        r=fixture();r.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("name","crystal-g");r.getAsJsonArray("outliner").get(0).getAsJsonObject().getAsJsonArray("children").remove(1);check(compile(r).vertices()==0,"informal markers ignored");
        r=fixture();r.remove("animations");var rigid=compile(r).manifest().getAsJsonArray("models").get(0).getAsJsonObject();
        check(rigid.get("rig").getAsString().equals("anim"),"unanimated models also require actual draw poses");
        var rv=rigid.getAsJsonArray("vertices");check(rv.equals(vertices),"removing animation must not bake fixed or group transforms into vertices");
        r.add("display",JsonParser.parseString("{\"fixed\":{\"translation\":[12,-8,5],\"rotation\":[40,70,15],\"scale\":[2,1,3]},\"thirdperson_righthand\":{\"translation\":[-8,4,0],\"rotation\":[90,0,0]}}"));
        check(compile(r).manifest().getAsJsonArray("models").get(0).getAsJsonObject().getAsJsonArray("vertices").equals(rv),"display context must come from AX, never from fixed at compile time");
        r.remove("display");
        JsonObject start=JsonParser.parseString("{\"uuid\":\"start\",\"name\":\"__flow_start\",\"origin\":[0,0,0],\"children\":[]}").getAsJsonObject();
        r.getAsJsonArray("outliner").add(start);rejected(r,"unpaired flow marker");
        JsonObject end=start.deepCopy();end.addProperty("uuid","end");end.addProperty("name","__flow_end");end.add("origin",JsonParser.parseString("[0,16,0]"));r.getAsJsonArray("outliner").add(end);
        check(compile(r).manifest().getAsJsonArray("models").get(0).getAsJsonObject().getAsJsonObject("flow").get("invLength").getAsFloat()==1,"flow in blocks");
        for(int i=0;i<50;i++)check(compile(fixture()).vertices()==12,"independent compiler invocations");
        System.out.println("BB compiler: hierarchy, same-draw bone mapping, nearest preset, multi-texture, UV rotation, invalid input and isolated reloads passed");
    }
}
