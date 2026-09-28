package com.nightstar.bloom;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import org.slf4j.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;
import static com.mojang.brigadier.arguments.FloatArgumentType.*;

public final class BloomClient implements ClientModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("NightstarBloom");
    public static BloomConfig config;
    private Object world;
    private boolean reloadFeedback;
    public void onInitializeClient(){
        config=BloomConfig.load();
        ClientCommandRegistrationCallback.EVENT.register((d,a)->{
            var root=literal("nsbloom");
            var diagnostics=literal("diagnostics");
            diagnostics.then(literal("viewport").then(argument("width",com.mojang.brigadier.arguments.IntegerArgumentType.integer(320,4096)).then(argument("height",com.mojang.brigadier.arguments.IntegerArgumentType.integer(240,2160)).executes(c->{
                if(!BloomRenderer.diagnostics){c.getSource().sendError(Text.literal("Enable diagnostics first"));return 0;}
                var window=net.minecraft.client.MinecraftClient.getInstance().getWindow();
                if(window.isFullscreen()){c.getSource().sendError(Text.literal("Use windowed mode for diagnostic viewport"));return 0;}
                long handle=window.getHandle();org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle);
                org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(handle,org.lwjgl.glfw.GLFW.GLFW_DECORATED,org.lwjgl.glfw.GLFW.GLFW_FALSE);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(handle,com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"width"),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"height"));
                return 1;
            }))));
            for(boolean on:new boolean[]{true,false})diagnostics.then(literal(on?"on":"off").executes(c->{BloomRenderer.setDiagnostics(on);c.getSource().sendFeedback(Text.literal("[星辉] diagnostics="+on+(on?" (counters reset)":"")));return 1;}));
            root.then(diagnostics);
            root.then(literal("status").executes(c->{c.getSource().sendFeedback(Text.literal(BloomRenderer.INSTANCE.status()));return 1;}));
            for(boolean on:new boolean[]{true,false})root.then(literal(on?"on":"off").executes(c->{config.enabled=on;config.save();c.getSource().sendFeedback(Text.literal("[星辉] enabled="+on));return 1;}));
            for(String key:new String[]{"strength","radius","core","halo"})root.then(literal(key).then(argument("value",floatArg(0,key.equals("radius")?2:4)).executes(c->{float v=getFloat(c,"value");if(key.equals("strength"))config.strength=v;else if(key.equals("core"))config.core=v;else if(key.equals("halo"))config.halo=v;else config.radius=v;config.save();return 1;})));
            var q=literal("quality");for(String s:new String[]{"low","medium","high"})q.then(literal(s).executes(c->{config.quality=s;config.save();return 1;}));root.then(q);
            var debug=literal("debug");for(String s:new String[]{"off","mask","scene","overlay"})debug.then(literal(s).executes(c->{config.debug=s;config.save();return 1;}));root.then(debug);
            var preset=literal("preset");for(String s:new String[]{"crystal","soft","restrained"})preset.then(literal(s).executes(c->{config.preset=s;config.save();return 1;}));root.then(preset);
            var motion=literal("motion");for(String s:new String[]{"static","breath","flow"})motion.then(literal(s).executes(c->{config.motion=s;config.save();return 1;}));root.then(motion);
            root.then(literal("flowSpeed").then(argument("value",floatArg(0,4)).executes(c->{config.flowSpeed=getFloat(c,"value");config.save();return 1;})));
            root.then(literal("color").then(argument("r",floatArg(0,4)).then(argument("g",floatArg(0,4)).then(argument("b",floatArg(0,4)).executes(c->{config.color=new float[]{getFloat(c,"r"),getFloat(c,"g"),getFloat(c,"b")};config.save();return 1;})))));
            root.then(literal("reload").executes(c->{config=BloomConfig.load();var client=net.minecraft.client.MinecraftClient.getInstance();reloadFeedback=false;client.reloadResources().whenComplete((v,error)->client.execute(()->{if(error!=null)c.getSource().sendFeedback(Text.literal("[星辉] Resource reload failed: "+error));else if(BloomRenderer.INSTANCE.disabledByIris())c.getSource().sendFeedback(Text.literal("[星辉] Resources loaded; Bloom is disabled because Iris is installed."));else if(!config.enabled)c.getSource().sendFeedback(Text.literal("[星辉] Config loaded; Bloom is off. Mesh validation will run when enabled."));else reloadFeedback=true;}));c.getSource().sendFeedback(Text.literal("[星辉] Resource reload requested"));return 1;}));root.then(literal("profile").then(argument("label",com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c->{
                String label=com.mojang.brigadier.arguments.StringArgumentType.getString(c,"label");
                if(!label.matches("[a-zA-Z0-9_-]{1,80}"))return 0;
                BloomProfiler.start(label,30);c.getSource().sendFeedback(Text.literal("[星辉] Sampling 30 seconds"));return 1;})));
            d.register(root);
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){
            public Identifier getFabricId(){return Identifier.of("nightstar_bloom","reload");}
            public void reload(ResourceManager manager){BloomRenderer.INSTANCE.requestReload();}
        });
        // The reload listener above already marks the manifests dirty; leaving a world releases everything, entering one does not need to.
        ClientTickEvents.END_CLIENT_TICK.register(c->{if(world!=c.world){if(world!=null)BloomRenderer.INSTANCE.close();world=c.world;}});
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            var renderer=BloomRenderer.INSTANCE;
            if(reloadFeedback&&c.player!=null&&!renderer.reloadState.equals("pending")){
                reloadFeedback=false;
                String result=renderer.reloadState.equals("complete")?"Resources and Bloom meshes loaded":renderer.reloadState+": "+renderer.loadError;
                c.player.sendMessage(Text.literal("[星辉] "+result),false);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->BloomRenderer.INSTANCE.close());
        LOG.info("[星辉]Nightstar Bloom initialized");
    }
}
