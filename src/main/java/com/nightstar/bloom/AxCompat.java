package com.nightstar.bloom;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional ArcartX bridge. Bloom never calls into AX at render time: whether AX drew an item is observed from
 * the render itself (AX cancels ItemRenderState.render, see ItemRendererMixin). This class only checks, once
 * per resource load, that the AX build still exposes the hook this contract was verified against, and reports
 * the outcome through {@link #status()}. Probe failures are logged once with their stack trace, never swallowed.
 */
public final class AxCompat {
    private static final String HOOK = "priv.seventeen.artist.arcartx.fabric.core.ArcartXHook";
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("arcartx");
    private static final String VERSION = FabricLoader.getInstance().getModContainer("arcartx")
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    private static String state = LOADED ? "unprobed" : "absent";
    private static String lastError = "";
    private AxCompat() {}
    public static boolean loaded() { return LOADED; }
    public static String version() { return VERSION; }
    /** Render-thread only; cheap after the first call. */
    public static void probe() {
        if (!state.equals("unprobed")) return;
        try {
            Class<?> hook = Class.forName(HOOK, false, AxCompat.class.getClassLoader());
            boolean render = false, key = false;
            for (var m : hook.getMethods()) {
                if (m.getName().equals("renderItem") && m.getReturnType() == boolean.class) render = true;
                if (m.getName().equals("getCustomItemModelId") && m.getReturnType() == String.class) key = true;
            }
            state = render && key ? "ready" : "hook-changed";
            if (!render || !key) BloomClient.LOG.warn("ArcartX {} hook no longer matches the verified contract (renderItem={}, getCustomItemModelId={}); AX items may lose Bloom", VERSION, render, key);
        } catch (ReflectiveOperationException | LinkageError e) {
            state = "probe-failed";
            lastError = e.toString();
            BloomClient.LOG.error("ArcartX bridge probe failed ({} {})", HOOK, VERSION, e);
        }
    }
    public static String status() {
        return (LOADED ? VERSION : "absent") + "/" + state + (lastError.isEmpty() ? "" : " axLastError=" + lastError);
    }
    public static String normalize(String id) {
        if (id == null || id.isBlank()) return null;
        return id.startsWith("arcartx_geo:") ? id.substring("arcartx_geo:".length()) : id;
    }
}
