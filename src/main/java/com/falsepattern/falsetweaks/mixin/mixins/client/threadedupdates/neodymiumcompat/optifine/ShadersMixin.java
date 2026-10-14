package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumShadowCulling;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shadersmod.client.Shaders;

import java.nio.FloatBuffer;

/** Supplies the current shadow-space transform to the Neodymium culling bridge. */
@Mixin(value = Shaders.class, remap = false)
public abstract class ShadersMixin {
    @Shadow @Final static FloatBuffer shadowModelView;

    @Inject(method = "setCameraShadow", at = @At("RETURN"), require = 1)
    private static void ft$captureShadowModelView(CallbackInfo ci) {
        shadowModelView.position(0);
        NeodymiumShadowCulling.SHADOW_MODEL_VIEW.set(shadowModelView);
    }
}
