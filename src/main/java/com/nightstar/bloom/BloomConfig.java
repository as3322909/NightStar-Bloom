package com.nightstar.bloom;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

public final class BloomConfig {
    public boolean enabled=true;
    public float strength=1, radius=1, core=1.65f, halo=1.0f;
    public float[] color={1,1,1};
    public String quality="medium", debug="off", preset="crystal", motion="static";
    public float flowSpeed=0.55f;
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("nightstar_bloom.json");
    public static BloomConfig load() {
        try {
            BloomConfig c=Files.exists(FILE)?new GsonBuilder().create().fromJson(Files.readString(FILE),BloomConfig.class):new BloomConfig();
            if(c==null||!Float.isFinite(c.halo)||c.halo<0||c.halo>4||!Float.isFinite(c.core)||c.core<0||c.core>4||!Float.isFinite(c.strength)||c.strength<0||c.strength>4||!Float.isFinite(c.radius)||c.radius<0||c.radius>2
                ||c.color==null||c.color.length!=3||!java.util.Set.of("low","medium","high").contains(c.quality)||!java.util.Set.of("off","mask","scene","overlay").contains(c.debug)
                ||!java.util.Set.of("crystal","soft","restrained").contains(c.preset)||!java.util.Set.of("static","breath","flow").contains(c.motion)
                ||!Float.isFinite(c.flowSpeed)||c.flowSpeed<0||c.flowSpeed>4)throw new IllegalArgumentException("Invalid bloom config");
            for(float v:c.color)if(!Float.isFinite(v)||v<0||v>4)throw new IllegalArgumentException("Invalid color");
            return c;
        }catch(Exception e){BloomClient.LOG.error("Bloom config invalid; using defaults",e);return new BloomConfig();}
    }
    public void save(){try{Files.createDirectories(FILE.getParent());Files.writeString(FILE,new GsonBuilder().setPrettyPrinting().create().toJson(this));}catch(Exception e){BloomClient.LOG.error("Cannot save Bloom config",e);}}
}
