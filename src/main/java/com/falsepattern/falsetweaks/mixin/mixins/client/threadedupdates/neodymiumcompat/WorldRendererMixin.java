package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.ICapturableTessellator;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.IRendererUpdateTaskHolder;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler.UpdateTask;
import com.falsepattern.falsetweaks.modules.threadedupdates.ThreadTessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.EntityLivingBase;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin implements IRendererUpdateTaskHolder {
    @Unique
    private NeodymiumChunkUpdateScheduler.UpdateTask ft$neodymiumUpdateTask;

    @Shadow
    protected abstract void postRenderBlocks(int pass, EntityLivingBase entity);

    @Override
    public UpdateTask ft$getNeodymiumUpdateTask() {
        if (ft$neodymiumUpdateTask == null) {
            ft$neodymiumUpdateTask = new UpdateTask();
        }
        return ft$neodymiumUpdateTask;
    }

    @Redirect(method = "updateRenderer",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/WorldRenderer;postRenderBlocks(ILnet/minecraft/entity/EntityLivingBase;)V"),
              require = 1)
    private void ft$mergeWorkerGeometry(WorldRenderer instance, int pass, EntityLivingBase entity) {
        UpdateTask task = ft$getNeodymiumUpdateTask();
        if (pass >= 0 && pass < 2 && task.started && task.finished && !task.cancelled) {
            ((ICapturableTessellator) ThreadTessellator.mainThreadTessellator())
                    .ft$appendVertexState(task.result[pass].vertexState);
        }
        postRenderBlocks(pass, entity);
    }

    @Inject(method = "markDirty", at = @At("RETURN"), require = 1)
    private void ft$cancelWorkerBuild(CallbackInfo ci) {
        NeodymiumChunkUpdateScheduler.INSTANCE.cancel((WorldRenderer) (Object) this);
    }
}
