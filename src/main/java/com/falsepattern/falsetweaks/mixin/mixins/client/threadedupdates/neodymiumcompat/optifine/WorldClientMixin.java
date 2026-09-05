package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import com.falsepattern.falsetweaks.modules.threading.MainThreadContainer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.ISaveHandler;

@Mixin(WorldClient.class)
public abstract class WorldClientMixin extends World {
    @Dynamic @Shadow(remap = false) public boolean renderItemInFirstPerson;

    protected WorldClientMixin(ISaveHandler saveHandler, String name, WorldProvider provider,
                               WorldSettings settings, Profiler profiler) {
        super(saveHandler, name, provider, settings, profiler);
    }

    @Dynamic
    @Redirect(method = {"getLightBrightnessForSkyBlocks", "func_72802_i"},
              at = @At(value = "FIELD",
                       target = "Lnet/minecraft/client/multiplayer/WorldClient;renderItemInFirstPerson:Z",
                       remap = false),
              require = 1)
    private boolean ft$noHandLightOffThread(WorldClient self) {
        return renderItemInFirstPerson && MainThreadContainer.isMainThread();
    }
}
