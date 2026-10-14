from pathlib import Path

root = Path(r'E:\Ariamis_src\AriamisDistantHorizons\src\main\java\com\seibel\distanthorizons\common\render\openGl')

def replace(text, old, new):
    assert old in text, old
    return text.replace(old, new)

p = root / 'glObject/GLState.java'
s = p.read_text()
s = replace(s, 'import org.lwjgl.opengl.GL32;', 'import java.nio.IntBuffer;\nimport org.lwjgl.BufferUtils;')
s = replace(s, '\tprivate static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;', '\tprivate static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;\n\t// Scratch storage is only used during capture; each nested snapshot owns its values.\n\t// LWJGL 2 requires room for 16 values even for the four-component viewport.\n\tprivate static final ThreadLocal<IntBuffer> VIEW_SCRATCH =\n\t\tThreadLocal.withInitial(() -> BufferUtils.createIntBuffer(16));')
s = replace(s, '\t\tthis.view = new int[4];\njava.nio.IntBuffer viewBuf = org.lwjgl.BufferUtils.createIntBuffer(16);', '\t\tif (this.view == null) this.view = new int[4];\n\t\tIntBuffer viewBuf = VIEW_SCRATCH.get();\n\t\tviewBuf.clear();')
s = replace(s, '\t\tGLMC.glBlendFunc(this.blendSrcColor, this.blendDstColor);\n', '')
p.write_text(s, newline='\r\n')

p = root / 'GlDhMetaRenderer.java'
s = p.read_text()
s = replace(s, '\tprivate final boolean[] previousColorMask = new boolean[4];', '\tprivate final boolean[] previousColorMask = new boolean[4];\n\t// This renderer is confined to the render thread. Reuse native query buffers.\n\tprivate final java.nio.ByteBuffer colorMaskScratch = BufferUtils.createByteBuffer(16);\n\tprivate final java.nio.FloatBuffer clearColorScratch = BufferUtils.createFloatBuffer(16);\n\tprivate IntBuffer drawBuffersScratch;')
s = replace(s, '\t\t\tjava.nio.ByteBuffer colorMask = BufferUtils.createByteBuffer(16);', '\t\t\tjava.nio.ByteBuffer colorMask = this.colorMaskScratch;\n\t\t\tcolorMask.clear();')
s = replace(s, '\t\t\tIntBuffer drawBuffers = BufferUtils.createIntBuffer(this.previousDrawBuffers.length);', '\t\t\tif (this.drawBuffersScratch == null || this.drawBuffersScratch.capacity() < this.previousDrawBuffers.length)\n\t\t\t{\n\t\t\t\tthis.drawBuffersScratch = BufferUtils.createIntBuffer(this.previousDrawBuffers.length);\n\t\t\t}\n\t\t\tIntBuffer drawBuffers = this.drawBuffersScratch;\n\t\t\tdrawBuffers.clear();')
for indent in ('\t\t\t\t', '\t\t'):
    old = (indent + 'float[] clearColorValues = new float[4];\njava.nio.FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(16);\n'
        + indent + 'GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, fb);\n'
        + indent + 'fb.rewind();\n' + indent + 'fb.get(clearColorValues);\n'
        + indent + 'GL11.glClearColor(clearColorValues[0], clearColorValues[1], clearColorValues[2], 1.0f);')
    s = replace(s, old, indent + 'this.setOpaqueClearAlpha();')
s = replace(s, '\tpublic void bindLightmap(ILightMapWrapper lightMapWrapper)', '\tprivate void setOpaqueClearAlpha()\n\t{\n\t\tthis.clearColorScratch.clear();\n\t\tGL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, this.clearColorScratch);\n\t\tGL11.glClearColor(this.clearColorScratch.get(0), this.clearColorScratch.get(1),\n\t\t\tthis.clearColorScratch.get(2), 1.0f);\n\t}\n\n\tpublic void bindLightmap(ILightMapWrapper lightMapWrapper)')
p.write_text(s, newline='\r\n')
