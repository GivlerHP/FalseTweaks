package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler.UpdateTask;
import com.falsepattern.falsetweaks.modules.threading.MainThreadContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraftforge.client.ForgeHooksClient;

@Mixin(RenderBlocks.class)
public abstract class RenderBlocksMixin {
    /** Called by Threading_RenderBlocksASM. 0=continue, 1=false, 2=true. */
    @SuppressWarnings("unused")
    @Unique
    private int ft$cancelRenderDelegatedToDifferentThread(Block block, int renderType) {
        if (!MainThreadContainer.isMainThread() || !NeodymiumChunkUpdateScheduler.shouldRenderOffThread(block)) {
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
        if (!task.started || !task.finished || task.cancelled) {
            return 0;
        }
        return task.result[pass].renderedSomething ? 2 : 1;
    }
}
