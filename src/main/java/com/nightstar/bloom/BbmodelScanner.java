package com.nightstar.bloom;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reload-only discovery. AX's global resource directory is not a vanilla assets namespace. */
final class BbmodelScanner {
    record Snapshot(Map<Identifier,JsonObject> manifests,Map<String,byte[]> textures,Set<String> aliases,int models,long retainedBytes) {
        static Snapshot empty(){return new Snapshot(Map.of(),Map.of(),Set.of(),0,0);}
    }
    static Snapshot scan(ResourceManager manager)throws IOException{
        if(!AxCompat.loaded())return Snapshot.empty();
        Map<String,BbmodelCompiler.Compiled> models=new TreeMap<>();
        Path modelRoot=FabricLoader.getInstance().getGameDir().resolve("resourcepacks/ArcartX/resource/model");
        if(Files.isDirectory(modelRoot))try(var files=Files.walk(modelRoot)){
            var paths=files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.toString().endsWith(".bbmodel")).limit(4097).sorted().toList();
            if(paths.size()>4096)throw new IOException("AX automatic scan exceeds 4096 model files");
            for(Path path:paths){
                String alias=modelRoot.relativize(path).toString().replace('\\','/');alias=alias.substring(0,alias.length()-8);
                String input;try(var in=Files.newInputStream(path)){input=text(in);}
                if(!input.contains("__bloom"))continue;
                if(Files.exists(path.resolveSibling(path.getFileName().toString().replaceFirst("\\.bbmodel$",".axmeta.json"))))throw new IOException(path+": AX metadata transforms are not supported by automatic compilation");
                Path root=modelRoot.toRealPath();
                var compiled=BbmodelCompiler.compile(path.toString(),alias,JsonParser.parseString(input).getAsJsonObject(),name->{
                    Path relative=Path.of(name.replace('\\','/'));if(relative.isAbsolute())throw new IOException("texture must be relative to the resource pack: "+name);
                    Path image=path.getParent().resolve(relative).normalize();
                    if(!image.startsWith(modelRoot)||!image.toRealPath().startsWith(root))throw new IOException("texture escapes model directory: "+name);
                    try(var stream=Files.newInputStream(image)){return bytes(stream);}
                });
                add(models,alias,compiled);
            }
        }
        // Supports selected directory/ZIP packs and respects Minecraft's resource priority.
        for(var entry:new TreeMap<>(manager.findResources("model",id->id.getNamespace().equals("arcartx")&&id.getPath().endsWith(".bbmodel"))).entrySet()){
            Identifier id=entry.getKey();String input;try(var in=entry.getValue().getInputStream()){input=text(in);}
            if(!input.contains("__bloom"))continue;
            if(manager.getResource(Identifier.of(id.getNamespace(),id.getPath().replaceFirst("\\.bbmodel$",".axmeta.json"))).isPresent())throw new IOException(id+": AX metadata transforms are not supported by automatic compilation");
            String alias=id.getPath().substring(6,id.getPath().length()-8);
            var compiled=BbmodelCompiler.compile(id.toString(),alias,JsonParser.parseString(input).getAsJsonObject(),name->{
                String path=id.getPath().substring(0,id.getPath().lastIndexOf('/')+1)+name.replace('\\','/');
                if(path.contains("..")||name.contains(":"))throw new IOException("unsafe texture path: "+name);
                try(var in=manager.getResourceOrThrow(Identifier.of(id.getNamespace(),path)).getInputStream()){return bytes(in);}
            });
            add(models,alias,compiled);
        }
        Map<Identifier,JsonObject> manifests=new TreeMap<>();Map<String,byte[]> textures=new HashMap<>();Set<String> aliases=new HashSet<>();long size=0;
        for(var entry:models.entrySet()){
            var compiled=entry.getValue();if(compiled.manifest().getAsJsonArray("models").isEmpty())continue;
            manifests.put(Identifier.of("nightstar_bloom","auto/"+BbmodelCompiler.hash(entry.getKey())+".json"),compiled.manifest());
            textures.putAll(compiled.textures());aliases.add(entry.getKey());size+=estimate(compiled);
        }
        return new Snapshot(Map.copyOf(manifests),Map.copyOf(textures),Set.copyOf(aliases),manifests.size(),size);
    }
    private static long estimate(BbmodelCompiler.Compiled c){long size=(long)c.vertices()*5*32;for(byte[] b:c.textures().values()){var png=java.nio.ByteBuffer.wrap(b);size+=b.length+4L*png.getInt(16)*png.getInt(20);}return size;}
    private static void add(Map<String,BbmodelCompiler.Compiled> models,String alias,BbmodelCompiler.Compiled compiled)throws IOException{
        String key=alias.toLowerCase(Locale.ROOT);
        if(models.values().stream().mapToLong(BbmodelScanner::estimate).sum()+estimate(compiled)>128L*1024*1024)throw new IOException("automatic Bloom resources exceed the 128 MiB reload budget");
        if(models.putIfAbsent(key,compiled)!=null)throw new IOException("ambiguous AX auto model alias: "+alias);
    }
    private static byte[] bytes(InputStream in)throws IOException{byte[] b=in.readNBytes(BbmodelCompiler.MAX_BYTES+1);if(b.length>BbmodelCompiler.MAX_BYTES)throw new IOException("resource exceeds 32 MiB");return b;}
    private static String text(InputStream in)throws IOException{String text=new String(bytes(in),StandardCharsets.UTF_8);return text.startsWith("\uFEFF")?text.substring(1):text;}
}
