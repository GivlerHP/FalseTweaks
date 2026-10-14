package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;

/** Disable OptiFine's competing chunk-update scheduler while FalseTweaks owns it. */
@Mixin(GameSettings.class)
public abstract class GameSettingsMixin {
    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofFastRender:Z", remap = false),
              remap = false, require = 0)
    private void ft$disableFastRender(GameSettings self, boolean value) {
        ft$setBoolean(self, "ofFastRender", false);
    }

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofChunkUpdates:I", remap = false),
              remap = false, require = 0)
    private void ft$disableOFChunkUpdates(GameSettings self, int value) {
        ft$setInt(self, "ofChunkUpdates", 1);
    }

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofChunkUpdatesDynamic:Z", remap = false),
              remap = false, require = 0)
    private void ft$disableOFDynamicChunkUpdates(GameSettings self, boolean value) {
        ft$setBoolean(self, "ofChunkUpdatesDynamic", false);
    }

    @Dynamic
    @Inject(method = "updateChunkLoading", at = @At("HEAD"), remap = false, require = 0)
    private void ft$disableOFChunkLoading(CallbackInfo ci) {
        ft$setInt((GameSettings) (Object) this, "ofChunkLoading", 0);
    }

    @Redirect(method = "loadOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;advancedOpengl:Z"),
              require = 1)
    private void ft$disableAdvancedOpenGL(GameSettings self, boolean value) {
        self.advancedOpengl = false;
    }

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofOcclusionFancy:Z", remap = false),
              remap = false, require = 0)
    private void ft$disableFancyOcclusion(GameSettings self, boolean value) {
        ft$setBoolean(self, "ofOcclusionFancy", false);
    }

    @Unique
    private static void ft$setBoolean(GameSettings settings, String name, boolean value) {
        try {
            Field field = settings.getClass().getField(name);
            field.setBoolean(settings, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access OptiFine setting " + name, e);
        }
    }

    @Unique
    private static void ft$setInt(GameSettings settings, String name, int value) {
        try {
            Field field = settings.getClass().getField(name);
            field.setInt(settings, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access OptiFine setting " + name, e);
        }
    }
}
