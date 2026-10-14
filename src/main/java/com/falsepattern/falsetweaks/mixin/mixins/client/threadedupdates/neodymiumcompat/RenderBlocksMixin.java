package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler.UpdateTask;
import com.falsepattern.falsetweaks.modules.threading.MainThreadContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraftforge.client.ForgeHooksClient;

@Mixin(RenderBlocks.class)
public abstract class RenderBlocksMixin {
    @Shadow public boolean renderFromInside;
    @Shadow public boolean renderAllFaces;
    /** Called by Threading_RenderBlocksASM. 0=continue, 1=false, 2=true. */
    @SuppressWarnings("unused")
    @Unique
    private int ft$cancelRenderDelegatedToDifferentThread(Block block, int renderType, int x, int y, int z) {
        if (!MainThreadContainer.isMainThread() || renderFromInside || renderAllFaces) {
            return 0;
        }
        int pass = ForgeHooksClient.getWorldRenderPass();
        if (pass < 0 || pass > 1) {
            return 0;
        }
        WorldRenderer wr = NeodymiumChunkUpdateScheduler.currentMainRenderer();
        if (wr == null) {
            return 0;
        }
        UpdateTask task = NeodymiumChunkUpdateScheduler.task(wr);
        if (task == null || !task.committing || !task.result[pass].built(x - task.x, y - task.y, z - task.z)) {
            return 0;
        }
        return task.result[pass].renderedSomething ? 2 : 1;
    }
}
