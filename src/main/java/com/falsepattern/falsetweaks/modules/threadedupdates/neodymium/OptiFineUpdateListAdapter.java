package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import net.minecraft.client.renderer.WorldRenderer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;

/**
 * Presents OptiFine 1.7.10's CompactArrayList as a normal java.util.List.
 *
 * OptiFine changes RenderGlobal.worldRenderersToUpdate from List to its own
 * CompactArrayList class. That class intentionally exposes list-like methods
 * but does not implement java.util.List, so Mixin shadows compiled against
 * vanilla fail on the field descriptor and a plain cast is not sufficient.
 */
public final class OptiFineUpdateListAdapter extends AbstractList<WorldRenderer> {
    private final Object delegate;
    private final Method get;
    private final Method size;
    private final Method addAt;
    private final Method removeAt;
    private final Method clear;

    private OptiFineUpdateListAdapter(Object delegate) {
        this.delegate = delegate;
        Class<?> type = delegate.getClass();
        try {
            get = type.getMethod("get", int.class);
            size = type.getMethod("size");
            addAt = type.getMethod("add", int.class, Object.class);
            removeAt = type.getMethod("remove", int.class);
            clear = type.getMethod("clear");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unsupported OptiFine update-list type: " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<WorldRenderer> wrap(Object value) {
        if (value instanceof List) {
            return (List<WorldRenderer>) value;
        }
        if (value == null) {
            throw new IllegalStateException("RenderGlobal worldRenderersToUpdate is null");
        }
        return new OptiFineUpdateListAdapter(value);
    }

    public Object backingObject() {
        return delegate;
    }

    @Override
    public WorldRenderer get(int index) {
        return (WorldRenderer) invoke(get, index);
    }

    @Override
    public int size() {
        return ((Number) invoke(size)).intValue();
    }

    @Override
    public void add(int index, WorldRenderer element) {
        invoke(addAt, index, element);
        modCount++;
    }

    @Override
    public WorldRenderer remove(int index) {
        WorldRenderer removed = (WorldRenderer) invoke(removeAt, index);
        modCount++;
        return removed;
    }

    @Override
    public void clear() {
        invoke(clear);
        modCount++;
    }

    private Object invoke(Method method, Object... args) {
        try {
            return method.invoke(delegate, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access OptiFine CompactArrayList method " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("OptiFine CompactArrayList method failed: " + method.getName(), cause);
        }
    }
}
