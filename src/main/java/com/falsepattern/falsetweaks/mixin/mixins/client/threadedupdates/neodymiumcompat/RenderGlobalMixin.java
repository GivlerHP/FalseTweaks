package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkUpdateScheduler;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.OptiFineUpdateListAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.EntityLivingBase;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin {
    /**
     * OptiFine 1.7.10 changes RenderGlobal.worldRenderersToUpdate from java.util.List
     * to CompactArrayList. A regular @Shadow compiled against vanilla therefore has
     * the wrong descriptor and Mixin 0.8.7 rejects it before the game can start.
     * Resolve the field by name at runtime instead. OptiFine's CompactArrayList is
     * then exposed through a small java.util.List adapter.
     */
    @Unique
    private static volatile Field ft$worldRenderersToUpdateField;
    @Unique
    private Object ft$updateListBacking;
    @Unique
    private List<WorldRenderer> ft$updateListView;

    @SuppressWarnings("unchecked")
    @Unique
    private List<WorldRenderer> ft$getWorldRenderersToUpdate() {
        try {
            Field field = ft$worldRenderersToUpdateField;
            if (field == null) {
                field = ft$findWorldRenderersToUpdateField();
                field.setAccessible(true);
                ft$worldRenderersToUpdateField = field;
            }
            Object value = field.get(this);
            if (value != ft$updateListBacking || ft$updateListView == null) {
                ft$updateListBacking = value;
                ft$updateListView = OptiFineUpdateListAdapter.wrap(value);
            }
            return ft$updateListView;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access RenderGlobal worldRenderersToUpdate", e);
        }
    }

    @Unique
    private Field ft$findWorldRenderersToUpdateField() throws NoSuchFieldException {
        Class<?> cls = RenderGlobal.class;

        // 1.7.10 names for this field:
        //   MCP: worldRenderersToUpdate
        //   SRG: field_72767_j
        //   Notch/OptiFine runtime: t
        //
        // OptiFine transforms RenderGlobal before runtime deobfuscation has normalized
        // every member name, so in a real OptiFine client the field can remain `t`.
        for (String name : new String[]{"field_72767_j", "worldRenderersToUpdate", "t"}) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }

        // Last-resort compatibility lookup. OptiFine replaces the List field with its
        // non-List CompactArrayList implementation, which makes it uniquely identifiable
        // even if another transformer renames the field. Do not guess between ordinary
        // java.util.List fields because RenderGlobal contains several of those.
        for (Field candidate : cls.getDeclaredFields()) {
            Class<?> type = candidate.getType();
            String typeName = type.getName();
            if ("CompactArrayList".equals(type.getSimpleName()) || typeName.endsWith(".CompactArrayList")) {
                return candidate;
            }
        }

        StringBuilder fields = new StringBuilder();
        for (Field candidate : cls.getDeclaredFields()) {
            if (fields.length() != 0) {
                fields.append(", ");
            }
            fields.append(candidate.getName()).append(':').append(candidate.getType().getName());
        }
        throw new NoSuchFieldException(
                "field_72767_j / worldRenderersToUpdate / t; RenderGlobal fields=[" + fields + "]");
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"), require = 1)
    private void ft$reloadNeodymiumWorkers(CallbackInfo ci) {
        NeodymiumChunkUpdateScheduler.INSTANCE.reload((RenderGlobal) (Object) this);
    }

    @Inject(method = "setWorldAndLoadRenderers", at = @At("HEAD"), require = 1)
    private void ft$stopPreviousWorldWorkers(CallbackInfo ci) {
        // Unloading a world does not call loadRenderers, so stop the old session explicitly.
        NeodymiumChunkUpdateScheduler.INSTANCE.reload(null);
    }

    @Redirect(method = "markBlocksForUpdate",
              at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/WorldRenderer;needsUpdate:Z"),
              require = 1)
    private boolean ft$invalidatePendingSnapshot(WorldRenderer renderer) {
        // Vanilla does not call markDirty again for an already dirty section. Its in-flight
        // snapshot must still be invalidated when another block/light update arrives.
        NeodymiumChunkUpdateScheduler.INSTANCE.cancel(renderer);
        return renderer.needsUpdate;
    }

    /**
     * @author FalsePattern / Ariamis compatibility port
     * @reason Restore the pre-4.0 split-build scheduler when Neodymium is the terrain backend.
     */
    @Overwrite
    public boolean updateRenderers(EntityLivingBase view, boolean frustumOnly) {
        return NeodymiumChunkUpdateScheduler.INSTANCE.updateRenderers((RenderGlobal) (Object) this,
                                                                      ft$getWorldRenderersToUpdate(),
                                                                      view,
                                                                      frustumOnly);
    }
}
