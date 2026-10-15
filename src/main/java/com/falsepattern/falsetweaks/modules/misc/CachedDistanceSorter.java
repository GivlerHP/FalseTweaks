package com.falsepattern.falsetweaks.modules.misc;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Stable sort with one distance evaluation per entry and reusable primitive scratch. */
public final class CachedDistanceSorter<T> {
    private Object[] values = new Object[0], scratch = new Object[0];
    private double[] distances = new double[0], scratchDistances = new double[0];

    @SuppressWarnings("unchecked")
    public void sort(List<T> list, ToDoubleFunction<T> distance) {
        int count = list.size();
        if (count < 2) return;
        if (values.length < count) {
            int capacity = Math.max(count, Math.max(16, values.length * 2));
            values = new Object[capacity]; scratch = new Object[capacity];
            distances = new double[capacity]; scratchDistances = new double[capacity];
        }
        try {
            for (int i = 0; i < count; i++) {
                T value = list.get(i); values[i] = value;
                distances[i] = -distance.applyAsDouble(value);
            }
            for (int width = 1; width < count; width *= 2) {
                for (int start = 0; start < count; start += width * 2) {
                    int middle = Math.min(count, start + width), end = Math.min(count, start + width * 2);
                    int left = start, right = middle;
                    for (int out = start; out < end; out++) {
                        int input = right >= end || (left < middle && Double.compare(distances[left], distances[right]) <= 0)
                            ? left++ : right++;
                        scratch[out] = values[input]; scratchDistances[out] = distances[input];
                    }
                }
                Object[] swap = values; values = scratch; scratch = swap;
                double[] swapDistances = distances; distances = scratchDistances; scratchDistances = swapDistances;
            }
            for (int i = 0; i < count; i++) list.set(i, (T) values[i]);
        } finally {
            // Never retain TileEntities/worlds between frames, including failed distance callbacks.
            Arrays.fill(values, 0, count, null); Arrays.fill(scratch, 0, count, null);
        }
    }
}
