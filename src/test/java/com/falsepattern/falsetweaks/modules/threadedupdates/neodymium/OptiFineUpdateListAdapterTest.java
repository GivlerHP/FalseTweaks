package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import net.minecraft.client.renderer.WorldRenderer;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class OptiFineUpdateListAdapterTest {
    /** Same public signatures as OptiFine E7's non-List CompactArrayList. */
    public static class CompactList {
        final List<Object> data = new ArrayList<Object>();
        public Object get(int i) { return data.get(i); }
        public Object set(int i, Object value) { return data.set(i, value); }
        public int size() { return data.size(); }
        public void add(int i, Object value) { data.add(i, value); }
        public Object remove(int i) { return data.remove(i); }
        public void clear() { data.clear(); }
    }

    // No methods named get/set/size: construction must select the direct interface before reflection.
    private static class DirectList implements ICompactUpdateList {
        final CompactList data = new CompactList();
        public Object ft$get(int i) { return data.get(i); }
        public Object ft$set(int i, Object value) { return data.set(i, value); }
        public int ft$size() { return data.size(); }
        public void ft$add(int i, Object value) { data.add(i, value); }
        public Object ft$remove(int i) { return data.remove(i); }
        public void ft$clear() { data.clear(); }
    }

    private static void exercise(Object backing) {
        List<WorldRenderer> adapter = OptiFineUpdateListAdapter.wrap(backing);
        adapter.add(null);
        adapter.add(0, null);
        assertEquals(2, adapter.size());
        assertNull(adapter.set(0, null));
        assertNull(adapter.get(1));
        adapter.remove(1);
        assertEquals(1, adapter.size());
        adapter.clear();
        assertTrue(adapter.isEmpty());
    }

    @Test public void directBridgeImplementsFullListContract() { exercise(new DirectList()); }
    @Test public void reflectiveFallbackSupportsCompaction() { exercise(new CompactList()); }
    @Test public void vanillaListIsNotWrapped() {
        List<WorldRenderer> vanilla = new ArrayList<WorldRenderer>();
        assertSame(vanilla, OptiFineUpdateListAdapter.wrap(vanilla));
    }
}
