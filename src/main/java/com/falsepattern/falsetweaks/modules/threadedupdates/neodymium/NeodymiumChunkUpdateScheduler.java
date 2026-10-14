package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import com.falsepattern.falsetweaks.Share;
import com.falsepattern.falsetweaks.Tags;
import com.falsepattern.falsetweaks.api.PassTrackingTessellator;
import com.falsepattern.falsetweaks.config.ModuleConfig;
import com.falsepattern.falsetweaks.config.ThreadingConfig;
import com.falsepattern.falsetweaks.modules.threadedupdates.ThreadTessellator;
import com.falsepattern.falsetweaks.modules.threadedupdates.ThreadedUpdateHooks;
import com.falsepattern.falsetweaks.modules.threadedupdates.saftey.IBlockExt;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.shader.TesselatorVertexState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Workers build raw geometry; the client keeps WorldRenderer's Forge/Neodymium lifecycle.
 * Each submission owns its cache, coordinates and results. Retired sessions cannot publish into
 * a new world's queue or mutate a recycled renderer's next task.
 */
public final class NeodymiumChunkUpdateScheduler {
    public static final NeodymiumChunkUpdateScheduler INSTANCE = new NeodymiumChunkUpdateScheduler();
    private static final Set<Integer> UNSAFE_TYPES = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    // Render-thread only. Workers return before reading this in RenderBlocksMixin.
    private static WorldRenderer currentMainRenderer;
    private Session session;
    private RenderGlobal owner;
    private World world;
    private boolean registered;
    private boolean frameUpdated;
    private long sampleStart = System.nanoTime();
    private long mainNanos;
    private int frames, completed, cancelled, failed;
    private String debugLine = "FT chunks: waiting for first sample";

    private NeodymiumChunkUpdateScheduler() {}

    public static void register() {
        if (!INSTANCE.registered) {
            INSTANCE.registered = true;
            FMLCommonHandler.instance().bus().register(INSTANCE);
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            Share.log.info("Neodymium asynchronous split-build backend enabled");
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) frameUpdated = false;
    }

    @SubscribeEvent
    public void onDebugOverlay(RenderGameOverlayEvent.Text event) {
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo && session != null) {
            event.left.add(debugLine);
        }
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (Tags.MOD_ID.equals(event.modID) && Minecraft.getMinecraft().renderGlobal != null) {
            Minecraft.getMinecraft().renderGlobal.loadRenderers();
        }
    }

    public void reload(RenderGlobal renderGlobal) {
        if (session != null) session.close();
        session = null;
        owner = renderGlobal;
        world = renderGlobal == null ? null : renderGlobal.theWorld;
        if (world != null && ModuleConfig.THREADED_CHUNK_UPDATES()) {
            session = new Session(Math.max(1, Math.min(8, ThreadingConfig.CHUNK_UPDATE_THREADS)));
            Share.log.info("Creating {} Neodymium-compatible chunk builders", session.workers.length);
        }
    }

    /** true ends this frame's work; false would make EntityRenderer busy-poll worker queues. */
    public boolean updateRenderers(RenderGlobal renderGlobal, List<WorldRenderer> updates,
                                   EntityLivingBase view, boolean frustumOnly) {
        if (owner != renderGlobal || world != renderGlobal.theWorld) reload(renderGlobal);
        if (session == null || view == null) return true;
        // OptiFine/shaders and Neodymium's debug speed-up can invoke the scheduler repeatedly.
        // Enforce the budget across those calls, not independently for each invocation.
        if (frameUpdated) return true;
        frameUpdated = true;
        long start = System.nanoTime();
        long budget = Math.max(1, ThreadingConfig.NEODYMIUM_MAIN_THREAD_BUDGET) * 1000000L;
        consumeFinished(session, view, start, budget);
        compact(updates);
        schedule(session, updates, view, frustumOnly, start, budget);
        mainNanos += System.nanoTime() - start;
        frames++;
        if (start - sampleStart >= 1000000000L) {
            debugLine = String.format(java.util.Locale.ROOT,
                    "FT chunks: pending %d, commits %d, cancelled %d, fallback %d; main %.2f ms/frame",
                    session.pending.size(), completed, cancelled, failed, mainNanos / (frames * 1000000.0));
            sampleStart = start;
            mainNanos = 0;
            frames = completed = cancelled = failed = 0;
        }
        return true;
    }

    private void consumeFinished(Session active, EntityLivingBase view, long start, long budget) {
        int limit = Math.max(1, active.workers.length * 2);
        int commits = 0;
        UpdateTask job;
        while (commits < limit && (job = active.finished.poll()) != null) {
            active.pending.remove(job);
            WorldRenderer wr = job.renderer;
            if (task(wr) != job) continue;
            if (job.matchesRenderer() && wr.needsUpdate) {
                if (job.cancelled) cancelled++;
                // Do not retry a permanently failing worker task forever.
                if (job.failure != null) {
                    failed++;
                    Share.log.warn("Worker build failed at {},{},{}; rebuilding on the render thread",
                                   job.x, job.y, job.z, job.failure);
                }
                // Frequently changing sections must not starve through endless cancel/retry loops.
                job.committing = job.failure == null && !job.cancelled;
                job.cancelled = false;
                currentMainRenderer = wr;
                try {
                    wr.updateRenderer(view);
                } finally {
                    currentMainRenderer = null;
                    job.committing = false;
                    ((IRendererUpdateTaskHolder) wr).ft$setNeodymiumUpdateTask(null);
                }
                // Do not discard geometry halfway through a commit after a dirty notification.
                if (job.cancelled) wr.markDirty();
                commits++;
                completed++;
            } else {
                cancelled++;
                ((IRendererUpdateTaskHolder) wr).ft$setNeodymiumUpdateTask(null);
            }
            if (System.nanoTime() - start >= budget) break;
        }
    }

    private void schedule(Session active, List<WorldRenderer> updates, final EntityLivingBase view,
                          boolean frustumOnly, long start, long budget) {
        int room = active.workers.length * 2 - active.pending.size();
        if (room <= 0 || updates.isEmpty()) return;
        // Bounded shortlist instead of sorting the entire dirty world every frame.
        List<WorldRenderer> nearest = new ArrayList<WorldRenderer>(room);
        Comparator<WorldRenderer> priority = new Comparator<WorldRenderer>() {
            @Override
            public int compare(WorldRenderer a, WorldRenderer b) {
                if (a.isInFrustum != b.isInFrustum) return a.isInFrustum ? -1 : 1;
                return Float.compare(a.distanceToEntitySquared(view), b.distanceToEntitySquared(view));
            }
        };
        for (WorldRenderer wr : updates) {
            if (wr.worldObj == null || !wr.needsUpdate || task(wr) != null || (frustumOnly && !wr.isInFrustum)) continue;
            int index = Collections.binarySearch(nearest, wr, priority);
            if (index < 0) index = -index - 1;
            if (index < room) {
                nearest.add(index, wr);
                if (nearest.size() > room) nearest.remove(room);
            }
        }
        int submitted = 0;
        for (WorldRenderer wr : nearest) {
            // Allow one submission even after a slow commit, so building cannot starve.
            if (submitted > 0 && System.nanoTime() - start >= budget) break;
            if (task(wr) != null) continue;
            UpdateTask job = new UpdateTask(wr);
            try {
                job.cache = NeodymiumChunkCacheCompat.create(job.world, job.x - 1, job.y - 1, job.z - 1,
                                                             job.x + 17, job.y + 17, job.z + 17, 1);
            } catch (RuntimeException e) {
                job.failure = e;
            }
            ((IRendererUpdateTaskHolder) wr).ft$setNeodymiumUpdateTask(job);
            active.pending.add(job);
            if (job.failure == null) active.work.offer(job);
            else active.finished.offer(job);
            submitted++;
        }
    }

    /** Single linear compaction avoids repeated ArrayList shifts and removal scans per result. */
    private static void compact(List<WorldRenderer> updates) {
        int write = 0;
        int size = updates.size();
        for (int read = 0; read < size; read++) {
            WorldRenderer wr = updates.get(read);
            if (wr != null && (wr.needsUpdate || task(wr) != null)) {
                if (write != read) updates.set(write, wr);
                write++;
            }
        }
        for (int i = size - 1; i >= write; i--) updates.remove(i);
    }

    public void cancel(WorldRenderer wr) {
        UpdateTask job = task(wr);
        if (job != null) job.cancelled = true;
    }

    public static WorldRenderer currentMainRenderer() { return currentMainRenderer; }

    public static UpdateTask task(WorldRenderer wr) {
        return ((IRendererUpdateTaskHolder) wr).ft$getNeodymiumUpdateTask();
    }

    private static boolean shouldRenderOffThread(Block block) {
        int type = block.getRenderType();
        return type != 22 && !UNSAFE_TYPES.contains(type) && IBlockExt.of(block).ft$isThreadSafe();
    }

    private static void runTask(UpdateTask job, Session active) {
        ICapturableTessellator capture = null;
        boolean cacheStarted = false;
        try {
            if (job.cancelled || !active.running || job.cache.extendedLevelsInChunkCache()) return;
            Tessellator tess = ThreadTessellator.getThreadTessellator();
            capture = (ICapturableTessellator) tess;
            capture.ft$discard();
            NeodymiumChunkCacheCompat.renderStart(job.cache);
            cacheStarted = true;
            RenderBlocks blocks = new RenderBlocks(job.cache);
            for (int pass = 0; pass < 2 && !job.cancelled && active.running; pass++) {
                ThreadedUpdateHooks.setWorldRenderPass(pass);
                ((PassTrackingTessellator) tess).pass(pass);
                capture.ft$discard();
                Result result = job.result[pass];
                boolean started = false;
                for (int y = job.y; y < job.y + 16; y++) {
                    if (job.cancelled || !active.running) return;
                    for (int z = job.z; z < job.z + 16; z++) {
                        for (int x = job.x; x < job.x + 16; x++) {
                            Block block = job.cache.getBlock(x, y, z);
                            if (block.getMaterial() == Material.air || !shouldRenderOffThread(block)
                                || !block.canRenderInPass(pass)) continue;
                            if (!started) {
                                started = true;
                                tess.startDrawingQuads();
                                tess.setTranslation(-job.x, -job.y, -job.z);
                            }
                            try {
                                result.renderedSomething |= blocks.renderBlockByRenderType(block, x, y, z);
                                result.markBuilt(x - job.x, y - job.y, z - job.z);
                            } catch (Throwable e) {
                                if (UNSAFE_TYPES.add(block.getRenderType())) {
                                    Share.log.warn("Disabling worker rendering for block render type {}", block.getRenderType());
                                }
                                throw e;
                            }
                        }
                    }
                }
                // Sort the merged translucent pass once, in WorldRenderer.postRenderBlocks.
                if (started) result.vertexState = capture.ft$getUnsortedVertexState();
            }
        } catch (Throwable e) {
            job.failure = e;
        } finally {
            ThreadedUpdateHooks.resetWorldRenderPass();
            try {
                if (capture != null) capture.ft$discard();
                if (cacheStarted) NeodymiumChunkCacheCompat.renderFinish(job.cache);
            } catch (Exception e) {
                job.failure = e;
            } finally {
                job.cache = null;
                if (active.running) active.finished.offer(job);
            }
        }
    }

    public static final class Result {
        public boolean renderedSomething;
        public TesselatorVertexState vertexState;
        private final long[] built = new long[64];

        private void markBuilt(int x, int y, int z) {
            int index = (y << 8) | (z << 4) | x;
            built[index >>> 6] |= 1L << (index & 63);
        }

        public boolean built(int x, int y, int z) {
            if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) return false;
            int index = (y << 8) | (z << 4) | x;
            return (built[index >>> 6] & (1L << (index & 63))) != 0;
        }
    }

    public static final class UpdateTask {
        public final WorldRenderer renderer;
        public final World world;
        public final int x, y, z;
        public final Result[] result = {new Result(), new Result()};
        public volatile boolean cancelled;
        public boolean committing;
        private Throwable failure;
        private ChunkCache cache;

        private UpdateTask(WorldRenderer renderer) {
            this.renderer = renderer;
            world = renderer.worldObj;
            x = renderer.posX;
            y = renderer.posY;
            z = renderer.posZ;
        }

        private boolean matchesRenderer() {
            return renderer.worldObj == world && renderer.posX == x && renderer.posY == y && renderer.posZ == z;
        }
    }

    private static final class Session {
        private final BlockingQueue<UpdateTask> work = new LinkedBlockingQueue<UpdateTask>();
        private final ConcurrentLinkedQueue<UpdateTask> finished = new ConcurrentLinkedQueue<UpdateTask>();
        // Client-owned. Workers publish results through finished, never mutate pending.
        private final List<UpdateTask> pending = new ArrayList<UpdateTask>();
        private final Thread[] workers;
        private volatile boolean running = true;

        private Session(int count) {
            workers = new Thread[count];
            for (int i = 0; i < count; i++) {
                workers[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (running) {
                            try {
                                runTask(work.take(), Session.this);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }, "FT Neodymium Chunk Worker #" + i);
                workers[i].setDaemon(true);
                workers[i].start();
            }
        }

        private void close() {
            running = false;
            for (UpdateTask job : pending) {
                job.cancelled = true;
                if (task(job.renderer) == job) ((IRendererUpdateTaskHolder) job.renderer).ft$setNeodymiumUpdateTask(null);
            }
            work.clear();
            finished.clear();
            pending.clear();
            for (Thread worker : workers) worker.interrupt();
            // No joins on the render thread: obsolete jobs own isolated storage and retire themselves.
        }
    }
}
