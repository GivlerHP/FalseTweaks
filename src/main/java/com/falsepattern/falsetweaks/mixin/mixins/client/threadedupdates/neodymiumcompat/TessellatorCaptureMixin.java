package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.ICapturableTessellator;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.NeodymiumChunkCacheCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.TesselatorVertexState;

import java.util.Arrays;

@Mixin(Tessellator.class)
public abstract class TessellatorCaptureMixin implements ICapturableTessellator {
    @Shadow(aliases = {"rawBufferSize"}, remap = false)
    public int field_78388_E;
    @Shadow private int[] rawBuffer;
    @Shadow private int rawBufferIndex;
    @Shadow private int vertexCount;
    @Shadow private boolean isDrawing;
    @Shadow private boolean hasTexture;
    @Shadow private boolean hasBrightness;
    @Shadow private boolean hasColor;
    @Shadow private boolean hasNormals;
    @Shadow protected abstract void reset();

    @Override
    public TesselatorVertexState ft$getUnsortedVertexState() {
        if (vertexCount < 1) {
            return null;
        }
        return new TesselatorVertexState(Arrays.copyOf(rawBuffer, rawBufferIndex),
                                         rawBufferIndex,
                                         vertexCount,
                                         hasTexture,
                                         hasBrightness,
                                         hasNormals,
                                         hasColor);
    }

    @Override
    public void ft$appendVertexState(TesselatorVertexState state) {
        if (state == null) {
            return;
        }
        hasTexture |= state.getHasTexture();
        hasBrightness |= state.getHasBrightness();
        hasColor |= state.getHasColor();
        hasNormals |= state.getHasNormals();

        if (field_78388_E == 0) {
            field_78388_E = 0x10000;
        }
        int required = rawBufferIndex + state.getRawBufferIndex();
        while (field_78388_E < required) {
            field_78388_E *= 2;
        }
        if (field_78388_E > rawBuffer.length) {
            rawBuffer = Arrays.copyOf(rawBuffer, field_78388_E);
            NeodymiumChunkCacheCompat.resizeNativeBuffers((Tessellator) (Object) this);
        }

        System.arraycopy(state.getRawBuffer(), 0, rawBuffer, rawBufferIndex, state.getRawBufferIndex());
        rawBufferIndex += state.getRawBufferIndex();
        vertexCount += state.getVertexCount();
    }

    @Override
    public void ft$discard() {
        isDrawing = false;
        reset();
    }
}
