package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.junit.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import static org.junit.Assert.*;

/** Lifecycle regression tests without an OpenGL context. Only the rendering callback is replaced. */
public class NeodymiumSchedulerTest {
    public static class Renderer extends WorldRenderer implements IRendererUpdateTaskHolder {
        NeodymiumChunkUpdateScheduler.UpdateTask job;
        int commits;
        boolean usedWorkerGeometry;
        boolean dirtyDuringCommit;
        private Renderer() { super(null, new ArrayList(), 0, 0, 0, 0); }
        @Override public NeodymiumChunkUpdateScheduler.UpdateTask ft$getNeodymiumUpdateTask() { return job; }
        @Override public void ft$setNeodymiumUpdateTask(NeodymiumChunkUpdateScheduler.UpdateTask value) { job = value; }
        @Override public void updateRenderer(EntityLivingBase camera) {
            commits++;
            needsUpdate = false;
            if (dirtyDuringCommit) job.cancelled = true;
            usedWorkerGeometry = job.committing;
        }
    }

    private static Renderer renderer() throws Exception {
        // WorldRenderer's constructor allocates GL display lists; the scheduler only needs its fields.
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Renderer wr = (Renderer) ((Unsafe) f.get(null)).allocateInstance(Renderer.class);
        wr.needsUpdate = true;
        return wr;
    }

    private static NeodymiumChunkUpdateScheduler.UpdateTask job(Renderer wr) throws Exception {
        Constructor<NeodymiumChunkUpdateScheduler.UpdateTask> c =
                NeodymiumChunkUpdateScheduler.UpdateTask.class.getDeclaredConstructor(WorldRenderer.class);
        c.setAccessible(true);
        wr.job = c.newInstance(wr);
        return wr.job;
    }

    private static Object field(Object instance, String name) throws Exception {
        Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }

    private static final class Fixture implements AutoCloseable {
        final Object session;
        final Class<?> sessionType;
        final NeodymiumChunkUpdateScheduler scheduler;
        Fixture() throws Exception {
            sessionType = Class.forName(NeodymiumChunkUpdateScheduler.class.getName() + "$Session");
            Constructor<?> c = sessionType.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            session = c.newInstance(1);
            Constructor<NeodymiumChunkUpdateScheduler> s = NeodymiumChunkUpdateScheduler.class.getDeclaredConstructor();
            s.setAccessible(true);
            scheduler = s.newInstance();
        }
        @SuppressWarnings("unchecked")
        void finish(NeodymiumChunkUpdateScheduler.UpdateTask job) throws Exception {
            ((List<Object>) field(session, "pending")).add(job);
            ((Queue<Object>) field(session, "finished")).offer(job);
        }
        void consume(long budget) throws Exception {
            Method m = NeodymiumChunkUpdateScheduler.class.getDeclaredMethod("consumeFinished", sessionType,
                    EntityLivingBase.class, long.class, long.class);
            m.setAccessible(true);
            m.invoke(scheduler, session, null, System.nanoTime(), budget);
        }
        @Override public void close() throws Exception {
            Method m = sessionType.getDeclaredMethod("close");
            m.setAccessible(true);
            m.invoke(session);
        }
    }

    @Test public void recycledRendererDoesNotConsumeOldCoordinates() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer wr = renderer();
            f.finish(job(wr));
            wr.posX = 16;
            f.consume(Long.MAX_VALUE);
            assertEquals(0, wr.commits);
            assertTrue(wr.needsUpdate);
            assertNull(wr.job);
        }
    }

    @Test public void obsoleteCompletionCannotClearNewTask() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer wr = renderer();
            f.finish(job(wr));
            Object replacement = job(wr);
            f.consume(Long.MAX_VALUE);
            assertSame(replacement, wr.job);
            assertEquals(0, wr.commits);
        }
    }

    @Test public void dirtyDuringCommitPreservesGeometryAndRequestsRebuild() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer wr = renderer();
            wr.dirtyDuringCommit = true;
            f.finish(job(wr));
            f.consume(Long.MAX_VALUE);
            assertTrue(wr.usedWorkerGeometry);
            assertTrue(wr.needsUpdate);
            assertNull(wr.job);
        }
    }

    @Test public void cancelledSnapshotFallsBackInsteadOfStarving() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer wr = renderer();
            job(wr).cancelled = true;
            f.finish(wr.job);
            f.consume(Long.MAX_VALUE);
            assertEquals(1, wr.commits);
            assertFalse(wr.usedWorkerGeometry);
            assertFalse(wr.needsUpdate);
        }
    }

    @Test public void expiredBudgetLeavesRemainingCommitsForNextFrame() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer first = renderer(), second = renderer();
            f.finish(job(first));
            f.finish(job(second));
            f.consume(0L);
            assertEquals(1, first.commits);
            assertEquals(0, second.commits);
            f.consume(Long.MAX_VALUE);
            assertEquals(1, second.commits);
        }
    }

    @Test public void closingSessionDetachesAndCancelsOutstandingJobs() throws Exception {
        try (Fixture f = new Fixture()) {
            Renderer wr = renderer();
            NeodymiumChunkUpdateScheduler.UpdateTask old = job(wr);
            f.finish(old);
            f.close();
            assertTrue(old.cancelled);
            assertNull(wr.job);
            assertTrue(((Queue<?>) field(f.session, "finished")).isEmpty());
        }
    }

    @Test public void compactPreservesDirtyAndInFlightRenderersInOrder() throws Exception {
        Renderer clean = renderer(), pending = renderer(), dirty = renderer();
        clean.needsUpdate = pending.needsUpdate = false;
        job(pending);
        List<WorldRenderer> list = new ArrayList<WorldRenderer>(Arrays.asList(null, clean, pending, null, dirty));
        Method m = NeodymiumChunkUpdateScheduler.class.getDeclaredMethod("compact", List.class);
        m.setAccessible(true);
        m.invoke(null, list);
        assertEquals(Arrays.asList(pending, dirty), list);
    }

    @Test public void geometryMaskSeparatesPassesAndRejectsOutsideSection() throws Exception {
        NeodymiumChunkUpdateScheduler.Result a = new NeodymiumChunkUpdateScheduler.Result();
        NeodymiumChunkUpdateScheduler.Result b = new NeodymiumChunkUpdateScheduler.Result();
        Method mark = a.getClass().getDeclaredMethod("markBuilt", int.class, int.class, int.class);
        mark.setAccessible(true);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (((x + y + z) & 1) == 0) mark.invoke(a, x, y, z);
        }
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(((x + y + z) & 1) == 0, a.built(x, y, z));
            assertFalse(b.built(x, y, z));
        }
        assertFalse(a.built(-1, 0, 0));
        assertFalse(a.built(0, 16, 0));
    }
}
