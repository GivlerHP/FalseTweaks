package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.settings.GameSettings;

/** Disable OptiFine's competing chunk-update scheduler while FalseTweaks owns it. */
@Mixin(GameSettings.class)
public abstract class GameSettingsMixin {
    @Dynamic @Shadow(remap = false) public boolean ofFastRender;
    @Dynamic @Shadow(remap = false) public int ofChunkUpdates;
    @Dynamic @Shadow(remap = false) public boolean ofChunkUpdatesDynamic;

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofFastRender:Z", remap = false),
              remap = false, require = 1)
    private void ft$disableFastRender(GameSettings self, boolean value) {
        ofFastRender = false;
    }

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofChunkUpdates:I", remap = false),
              remap = false, require = 2)
    private void ft$disableOFChunkUpdates(GameSettings self, int value) {
        ofChunkUpdates = 1;
    }

    @Dynamic
    @Redirect(method = "loadOfOptions",
              at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                       target = "Lnet/minecraft/client/settings/GameSettings;ofChunkUpdatesDynamic:Z", remap = false),
              remap = false, require = 1)
    private void ft$disableOFDynamicChunkUpdates(GameSettings self, boolean value) {
        ofChunkUpdatesDynamic = false;
    }
}
