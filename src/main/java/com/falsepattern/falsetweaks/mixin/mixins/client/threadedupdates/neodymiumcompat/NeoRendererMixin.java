package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumShadowCulling;
import lombok.val;
import makamys.neodymium.Compat;
import makamys.neodymium.renderer.ChunkMesh;
import makamys.neodymium.renderer.GPUMemoryManager;
import makamys.neodymium.renderer.NeoRegion;
import makamys.neodymium.renderer.NeoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Restore the renderer bookkeeping Neodymium delegates to FalseTweaks 3.x. */
@Mixin(value = NeoRenderer.class, remap = false)
public abstract class NeoRendererMixin {
    @Shadow public boolean isFirstPass;
    @Shadow public boolean reloadPending;
    @Shadow private List<GPUMemoryManager> mems;
    @Shadow private List<NeoRegion> loadedRegionsList;

    @Invoker("updateRenderGlobalStats")
    private static void ft$updateRenderGlobalStats() {
        throw new AssertionError();
    }

    @Inject(method = "preRenderSortedRenderers", at = @At("HEAD"), require = 1)
    private void ft$reloadOnShaderPackChange(int renderPass, double alpha,
                                             WorldRenderer[] sortedWorldRenderers,
                                             CallbackInfoReturnable<Integer> cir) {
        boolean shadersActive = com.falsepattern.falsetweaks.Compat.optiFineHasShaders()
                && shadersmod.client.Shaders.getShaderPackName() != null;
        if (shadersActive != Compat.isOptiFineShadersEnabled()) {
            // Neodymium chooses its vertex writer and GL program at renderer creation.
            // Rebuilding only the shader leaves meshes in the previous vertex format.
            // Its render-tick END hook reloads WorldRenderers and recreates Neo safely.
            reloadPending = true;
        }
    }

    @Inject(method = "preRenderSortedRenderers",
            at = @At(value = "INVOKE",
                     target = "Lmakamys/neodymium/renderer/NeoRenderer;render(ID)I"),
            require = 1)
    private void ft$restoreRenderGlobalStats(int renderPass,
                                             double alpha,
                                             WorldRenderer[] sortedWorldRenderers,
                                             CallbackInfoReturnable<Integer> cir) {
        if (isFirstPass && Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            ft$updateRenderGlobalStats();
        }
    }

    /**
     * @author FalsePattern
     * @reason Avoid rendering every loaded terrain mesh during an OptiFine shadow pass.
     */
    @Overwrite
    private boolean isRendererVisible(WorldRenderer renderer, boolean shadowPass) {
        return shadowPass ? NeodymiumShadowCulling.isCasterVisible(renderer) : renderer.isVisible;
    }

    // loadedRegionsList is rebuilt inside initIndexBuffers. HEAD observes the previous frame
    // (or an empty list on first load), incorrectly removing all shadow casters.
    @Inject(method = "initIndexBuffers",
            at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V", shift = At.Shift.AFTER),
            require = 1)
    private void ft$prepareShadowCulling(boolean shadowPass, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!shadowPass) {
            return;
        }
        NeodymiumShadowCulling.begin();
        int regionCount = loadedRegionsList.size();
        for (val memory : mems) {
            for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
                val renderData = loadedRegionsList.get(regionIndex).getRenderData(memory);
                for (val mesh : renderData.getSentMeshes()) {
                    WorldRenderer renderer = ((ChunkMesh) mesh).wr();
                    if (renderer.isVisible && renderer.isInFrustum) {
                        NeodymiumShadowCulling.addReceiver(renderer);
                    }
                }
            }
        }
        NeodymiumShadowCulling.finishReceivers();
    }
}
