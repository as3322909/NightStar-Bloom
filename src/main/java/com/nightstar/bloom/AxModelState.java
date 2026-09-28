package com.nightstar.bloom;
public interface AxModelState {
    String nightstar$axModel();
    void nightstar$axModel(String model);
    /**
     * True once ItemRenderState.render ran to completion. ArcartX cancels that method at HEAD when it draws the
     * item itself, so a false value after the call means AX (or another mod) replaced the vanilla item model.
     */
    boolean nightstar$vanillaDrawn();
    void nightstar$vanillaDrawn(boolean drawn);
    /** Stack the state was last built from (ItemModelManagerMixin); hashed into AX's animation manager key. */
    void nightstar$axStack(net.minecraft.item.ItemStack stack);
    /** Temporary diagnostic: display context the state was last built for (ItemModelManagerMixin). */
    void nightstar$context(net.minecraft.item.ItemDisplayContext context);
    /**
     * Temporary diagnostic: read side of the above, so KeyedItemRenderStateMixin can bucket the override's
     * addModelKey calls by context without a second field.
     */
    net.minecraft.item.ItemDisplayContext nightstar$context();
}
