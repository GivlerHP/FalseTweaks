package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.client.renderer.WorldRenderer;

/** Limits Neodymium's shadow pass to casters that can reach a visible terrain section. */
public final class NeodymiumShadowCulling {
    public static final Matrix4f SHADOW_MODEL_VIEW = new Matrix4f();

    private static final Vector3f SCRATCH = new Vector3f();
    private static float receiverMinX;
    private static float receiverMinY;
    private static float receiverMinZ;
    private static float receiverMaxX;
    private static float receiverMaxY;
    private static float worldMinX;
    private static float worldMinY;
    private static float worldMinZ;
    private static float worldMaxX;
    private static float worldMaxY;
    private static float worldMaxZ;
    private static boolean hasReceivers;

    private NeodymiumShadowCulling() {
    }

    public static void begin() {
        receiverMinX = Float.POSITIVE_INFINITY;
        receiverMinY = Float.POSITIVE_INFINITY;
        receiverMinZ = Float.POSITIVE_INFINITY;
        receiverMaxX = Float.NEGATIVE_INFINITY;
        receiverMaxY = Float.NEGATIVE_INFINITY;
        worldMinX = Float.POSITIVE_INFINITY;
        worldMinY = Float.POSITIVE_INFINITY;
        worldMinZ = Float.POSITIVE_INFINITY;
        worldMaxX = Float.NEGATIVE_INFINITY;
        worldMaxY = Float.NEGATIVE_INFINITY;
        worldMaxZ = Float.NEGATIVE_INFINITY;
        hasReceivers = false;
    }

    public static void addReceiver(WorldRenderer renderer) {
        hasReceivers = true;
        worldMinX = Math.min(worldMinX, renderer.posX);
        worldMinY = Math.min(worldMinY, renderer.posY);
        worldMinZ = Math.min(worldMinZ, renderer.posZ);
        worldMaxX = Math.max(worldMaxX, renderer.posX + 16);
        worldMaxY = Math.max(worldMaxY, renderer.posY + 16);
        worldMaxZ = Math.max(worldMaxZ, renderer.posZ + 16);
    }

    public static void finishReceivers() {
        if (!hasReceivers) {
            return;
        }
        for (int corner = 0; corner < 8; corner++) {
            float cx = (corner & 1) == 0 ? worldMinX : worldMaxX;
            float cy = (corner & 2) == 0 ? worldMinY : worldMaxY;
            float cz = (corner & 4) == 0 ? worldMinZ : worldMaxZ;
            SHADOW_MODEL_VIEW.transformPosition(cx, cy, cz, SCRATCH);
            receiverMinX = Math.min(receiverMinX, SCRATCH.x);
            receiverMinY = Math.min(receiverMinY, SCRATCH.y);
            receiverMinZ = Math.min(receiverMinZ, SCRATCH.z);
            receiverMaxX = Math.max(receiverMaxX, SCRATCH.x);
            receiverMaxY = Math.max(receiverMaxY, SCRATCH.y);
        }
    }

    public static boolean isCasterVisible(WorldRenderer renderer) {
        if (!hasReceivers) {
            // No receiver information is not proof of invisibility (first frame, entity-only view).
            return true;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            float x = renderer.posX + ((corner & 1) == 0 ? 0 : 16);
            float y = renderer.posY + ((corner & 2) == 0 ? 0 : 16);
            float z = renderer.posZ + ((corner & 4) == 0 ? 0 : 16);
            SHADOW_MODEL_VIEW.transformPosition(x, y, z, SCRATCH);
            minX = Math.min(minX, SCRATCH.x);
            minY = Math.min(minY, SCRATCH.y);
            maxX = Math.max(maxX, SCRATCH.x);
            maxY = Math.max(maxY, SCRATCH.y);
            maxZ = Math.max(maxZ, SCRATCH.z);
        }
        return maxX >= receiverMinX && minX <= receiverMaxX
               && maxY >= receiverMinY && minY <= receiverMaxY
               && maxZ >= receiverMinZ;
    }
}
