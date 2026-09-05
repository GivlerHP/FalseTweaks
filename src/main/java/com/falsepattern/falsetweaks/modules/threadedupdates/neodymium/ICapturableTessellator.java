/*
 * Ariamis Neodymium compatibility layer for FalseTweaks 4.4.5.
 * Based on the split-build path used by FalseTweaks 3.9.x.
 */
package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import net.minecraft.client.shader.TesselatorVertexState;

public interface ICapturableTessellator {
    TesselatorVertexState ft$getUnsortedVertexState();
    void ft$appendVertexState(TesselatorVertexState state);
    void ft$discard();
}
