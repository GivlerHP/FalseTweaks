package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;

/** Remove OptiFine performance controls that conflict with threaded Neodymium updates. */
@Pseudo
@Mixin(targets = "GuiPerformanceSettingsOF", remap = false)
public abstract class GuiPerformanceSettingsMixin extends GuiScreen {
    @Dynamic @Shadow(remap = false) private static GameSettings.Options[] enumOptions;

    @Dynamic
    @Inject(method = "<clinit>", at = @At("RETURN"), remap = false)
    private static void ft$filterNeodymiumOptions(CallbackInfo ci) {
        List<GameSettings.Options> filtered = new ArrayList<GameSettings.Options>(enumOptions.length);
        for (GameSettings.Options option : enumOptions) {
            String name = option.name();
            if (!"FAST_RENDER".equals(name)
                && !"CHUNK_UPDATES".equals(name)
                && !"CHUNK_UPDATES_DYNAMIC".equals(name)) {
                filtered.add(option);
            }
        }
        enumOptions = filtered.toArray(new GameSettings.Options[filtered.size()]);
    }
}
