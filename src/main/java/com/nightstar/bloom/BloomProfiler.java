package com.nightstar.bloom;
import java.util.Arrays;
import java.nio.file.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
/** Opt-in bounded render-to-render intervals including limiter, not GPU timings. */
public final class BloomProfiler {
 private static long[] samples;private static int count;private static long end,last;private static String label;
 public static void start(String name,int seconds){
  if(samples!=null)throw new IllegalStateException("A sample is running");
  label=name;samples=new long[300000];count=0;last=0;end=System.nanoTime()+seconds*1_000_000_000L;
 }
 public static void frame(){
  if(samples==null)return;long now=System.nanoTime();
  if(now>=end||count>=samples.length){finish();return;}
  if(last!=0)samples[count++]=now-last;last=now;
 }
 private static void finish(){
  long[] sorted=Arrays.copyOf(samples,count);samples=null;Arrays.sort(sorted);if(count==0)return;
  double total=0,slow=0;int tail=Math.max(1,(int)Math.ceil(count*.01));
  for(long n:sorted)total+=n;for(int i=count-tail;i<count;i++)slow+=sorted[i];
  JsonObject o=new JsonObject();o.addProperty("label",label);o.addProperty("frames",count);o.addProperty("averageFps",count*1e9/total);
  o.addProperty("onePercentLowFps",tail*1e9/slow);o.addProperty("meanFrameMs",total/count/1e6);
  o.addProperty("medianFrameMs",(sorted[(count-1)/2]/2.0+sorted[count/2]/2.0)/1e6);
  o.addProperty("p99FrameMs",sorted[Math.min(count-1,(int)Math.ceil(count*.99)-1)]/1e6);
  o.addProperty("worstFrameMs",sorted[count-1]/1e6);o.addProperty("measurement","render intervals including limiter, not GPU time");
  o.addProperty("renderer",BloomRenderer.INSTANCE.status());
  var c=net.minecraft.client.MinecraftClient.getInstance();o.addProperty("width",c.getWindow().getFramebufferWidth());o.addProperty("height",c.getWindow().getFramebufferHeight());
  try{Path p=FabricLoader.getInstance().getGameDir().resolve("logs/nightstar-bloom-perf");Files.createDirectories(p);Files.writeString(p.resolve(label+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(o));}
  catch(Exception e){BloomClient.LOG.error("Could not write performance sample",e);}
 }
}
