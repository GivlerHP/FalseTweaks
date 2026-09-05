package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import com.falsepattern.falsetweaks.Compat;
import com.falsepattern.falsetweaks.config.ModuleConfig;
import com.falsepattern.falsetweaks.modules.cc.ChunkCacheFT;
import stubpackage.ChunkCacheOF;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;

import java.io.IOException;

/** Small subset of the 3.9.x OptiFine bridge needed by the Neodymium split builder. */
public final class NeodymiumChunkCacheCompat {
    private static Boolean hasChunkCacheOF;

    private NeodymiumChunkCacheCompat() {
    }

    public static ChunkCache create(World world,
                                    int xMin, int yMin, int zMin,
                                    int xMax, int yMax, int zMax,
                                    int padding) {
        if (hasChunkCacheOF == null) {
            boolean present = false;
            if (Compat.optiFineInstalled()) {
                try {
                    present = Launch.classLoader.getClassBytes("ChunkCacheOF") != null;
                } catch (IOException ignored) {
                }
            }
            hasChunkCacheOF = present;
        }

        if (hasChunkCacheOF) {
            return new ChunkCacheOF(world, xMin, yMin, zMin, xMax, yMax, zMax, padding);
        }
        if (ModuleConfig.DYNAMIC_LIGHTS || ModuleConfig.FASTER_CHUNK_CACHE) {
            return new ChunkCacheFT(world, xMin, yMin, zMin, xMax, yMax, zMax, padding);
        }
        return new ChunkCache(world, xMin, yMin, zMin, xMax, yMax, zMax, padding);
    }

    public static void renderStart(ChunkCache cache) {
        if (cache instanceof ChunkCacheOF) {
            ((ChunkCacheOF) cache).renderStart();
        } else if (cache instanceof ChunkCacheFT) {
            ((ChunkCacheFT) cache).renderStart();
        }
    }

    public static void renderFinish(ChunkCache cache) {
        if (cache instanceof ChunkCacheOF) {
            ((ChunkCacheOF) cache).renderFinish();
        } else if (cache instanceof ChunkCacheFT) {
            ((ChunkCacheFT) cache).renderFinish();
        }
    }

    public static void resizeNativeBuffers(Tessellator tessellator) {
        if (Compat.optiFineInstalled() && tessellator instanceof ITessellatorOptiFineCompat) {
            ((ITessellatorOptiFineCompat) tessellator).ft$resizeNativeBuffers();
        }
    }
}
