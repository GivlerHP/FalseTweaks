package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import com.falsepattern.falsetweaks.modules.threadedupdates.ThreadTessellator;
import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.ITessellatorOptiFineCompat;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.Tessellator;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

@Mixin(Tessellator.class)
public abstract class TessellatorMixin implements ITessellatorOptiFineCompat {
    @Shadow(aliases = {"rawBufferSize"}, remap = false)
    public int field_78388_E;
    @Dynamic @Shadow private ByteBuffer byteBuffer;
    @Dynamic @Shadow private IntBuffer intBuffer;
    @Dynamic @Shadow private FloatBuffer floatBuffer;
    @Dynamic @Shadow private ShortBuffer shortBuffer;

    @Dynamic
    @Redirect(method = "addVertex",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/GLAllocation;createDirectByteBuffer(I)Ljava/nio/ByteBuffer;"),
              require = 1)
    private ByteBuffer ft$avoidWorkerNativeReallocation(int size) {
        if ((Object) this != ThreadTessellator.mainThreadTessellator()) {
            return byteBuffer;
        }
        return GLAllocation.createDirectByteBuffer(size);
    }

    @Override
    public void ft$resizeNativeBuffers() {
        byteBuffer = GLAllocation.createDirectByteBuffer(field_78388_E * 4);
        intBuffer = byteBuffer.asIntBuffer();
        floatBuffer = byteBuffer.asFloatBuffer();
        shortBuffer = byteBuffer.asShortBuffer();
    }
}
