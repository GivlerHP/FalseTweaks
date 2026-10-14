package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;

/** Remove OptiFine controls whose values are owned by the Neodymium backend. */
@Mixin(GuiVideoSettings.class)
public abstract class GuiVideoSettingsMixin {
    @Dynamic @Shadow private static GameSettings.Options[] videoOptions;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void ft$filterNeodymiumOptions(CallbackInfo ci) {
        List<GameSettings.Options> filtered = new ArrayList<GameSettings.Options>(videoOptions.length);
        for (GameSettings.Options option : videoOptions) {
            String name = option.name();
            if (!"CHUNK_LOADING".equals(name) && !"ADVANCED_OPENGL".equals(name)) {
                filtered.add(option);
            }
        }
        videoOptions = filtered.toArray(new GameSettings.Options[filtered.size()]);
    }
}
