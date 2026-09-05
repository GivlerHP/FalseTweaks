package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

import com.falsepattern.falsetweaks.Share;
import com.falsepattern.falsetweaks.Tags;
import com.falsepattern.falsetweaks.config.ModuleConfig;
import com.falsepattern.falsetweaks.config.ThreadingConfig;
import com.falsepattern.falsetweaks.api.PassTrackingTessellator;
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
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Neodymium backend for FalseTweaks 4.x threaded chunk updates.
 *
 * This intentionally restores the conservative 3.9.x "split build" model rather than the old
 * experimental direct ChunkMesh construction: worker threads bake only renderers proven safe by
 * the 4.4.x thread-safety registry. The client thread then executes the normal WorldRenderer pass,
 * skips those already-baked blocks and appends the worker vertex state immediately before
 * postRenderBlocks(). Neodymium therefore sees one ordinary main-thread capture and does not need
 * any private API hooks.
 */
public final class NeodymiumChunkUpdateScheduler {
    public static final NeodymiumChunkUpdateScheduler INSTANCE = new NeodymiumChunkUpdateScheduler();

    /** Scope of the main-thread WorldRenderer currently consuming a worker result. */
    private static final ThreadLocal<WorldRenderer> CURRENT_MAIN_RENDERER = new ThreadLocal<WorldRenderer>();
    private static final Set<Integer> WARNED_RENDER_TYPES = Collections.synchronizedSet(new HashSet<Integer>());
    /** Render types which proved unsafe at runtime despite registry classification. */
    private static final Set<Integer> RUNTIME_UNSAFE_RENDER_TYPES = Collections.synchronizedSet(new HashSet<Integer>());

    private final BlockingQueue<WorldRenderer> workQueue = new LinkedBlockingQueue<WorldRenderer>();
    private final ConcurrentLinkedQueue<WorldRenderer> finishedQueue = new ConcurrentLinkedQueue<WorldRenderer>();
    private final AtomicInteger outstanding = new AtomicInteger();

    private volatile boolean running;
    private volatile long generation;
    private Worker[] workers = new Worker[0];
    private RenderGlobal owner;
    private boolean registered;

    private NeodymiumChunkUpdateScheduler() {
    }

    public static void register() {
        INSTANCE.registerInternal();
    }

    private synchronized void registerInternal() {
        if (registered) {
            return;
        }
        registered = true;
        FMLCommonHandler.instance().bus().register(this);
        Share.log.info("Neodymium threaded compatibility backend enabled");
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!Tags.MOD_ID.equals(event.modID)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    public synchronized void reload(RenderGlobal renderGlobal) {
        stopWorkers();
        owner = renderGlobal;
        generation++;
        workQueue.clear();
        finishedQueue.clear();
        outstanding.set(0);

        if (renderGlobal == null || renderGlobal.theWorld == null || !ModuleConfig.THREADED_CHUNK_UPDATES()) {
            return;
        }

        int count = Math.max(1, Math.min(8, ThreadingConfig.CHUNK_UPDATE_THREADS));
        running = true;
        workers = new Worker[count];
        String base = "FT Neodymium Chunk Worker #";
        for (int i = 0; i < count; i++) {
            workers[i] = new Worker(base + i, generation);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        Share.log.info("Creating {} Neodymium-compatible chunk builder{}", count, count == 1 ? "" : "s");
    }

    private synchronized void stopWorkers() {
        running = false;
        for (Worker worker : workers) {
            worker.interrupt();
        }
        for (Worker worker : workers) {
            try {
                worker.join(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers = new Worker[0];
    }

    /** Called from the RenderGlobal mixin instead of the vanilla update scheduler. */
    public boolean updateRenderers(RenderGlobal renderGlobal,
                                   List<WorldRenderer> updateList,
                                   EntityLivingBase view,
                                   boolean frustumOnly) {
        if (renderGlobal == null || renderGlobal.theWorld == null || view == null) {
            return false;
        }
        if (owner != renderGlobal || workers.length == 0) {
            reload(renderGlobal);
        }

        consumeFinished(updateList, view);
        compact(updateList);
        schedule(updateList, view, frustumOnly);

        return updateList.isEmpty() && outstanding.get() == 0;
    }

    private void consumeFinished(List<WorldRenderer> updateList, EntityLivingBase view) {
        int budget = Math.max(1, workers.length * 2);
        for (int i = 0; i < budget; i++) {
            WorldRenderer wr = finishedQueue.poll();
            if (wr == null) {
                return;
            }
            UpdateTask task = task(wr);

            if (!task.started || task.generation != generation) {
                finishTask(task);
                continue;
            }
            if (task.failure != null) {
                Share.log.warn("Worker failed to prebuild chunk renderer at {},{},{}; falling back to a fresh build",
                               wr.posX, wr.posY, wr.posZ, task.failure);
                task.cancelled = true;
            }
            if (task.cancelled || !wr.needsUpdate) {
                finishTask(task);
                // If it is still dirty it remains in the vanilla update list and is scheduled again below.
                if (!wr.needsUpdate) {
                    removeIdentity(updateList, wr);
                }
                continue;
            }

            CURRENT_MAIN_RENDERER.set(wr);
            try {
                wr.updateRenderer(view);
            } catch (RuntimeException | Error t) {
                // Do not permanently swallow a render failure. Mark dirty so the next pass can try again,
                // then propagate to preserve normal crash diagnostics.
                wr.markDirty();
                throw t;
            } finally {
                CURRENT_MAIN_RENDERER.remove();
            }

            boolean dirtiedDuringCommit = task.cancelled;
            finishTask(task);
            removeIdentity(updateList, wr);
            if (dirtiedDuringCommit) {
                wr.markDirty();
                if (!containsIdentity(updateList, wr)) {
                    updateList.add(wr);
                }
            }
        }
    }

    private void schedule(List<WorldRenderer> updateList, final EntityLivingBase view, boolean frustumOnly) {
        int maxOutstanding = Math.max(4, workers.length * 8);
        int room = maxOutstanding - outstanding.get();
        if (room <= 0 || updateList.isEmpty()) {
            return;
        }

        ArrayList<WorldRenderer> candidates = new ArrayList<WorldRenderer>();
        for (WorldRenderer wr : updateList) {
            if (wr == null || !wr.needsUpdate || wr.worldObj == null || (frustumOnly && !wr.isInFrustum)) {
                continue;
            }
            UpdateTask task = task(wr);
            if (!task.started) {
                candidates.add(wr);
            }
        }
        Collections.sort(candidates, new Comparator<WorldRenderer>() {
            @Override
            public int compare(WorldRenderer a, WorldRenderer b) {
                return Float.compare(a.distanceToEntitySquared(view), b.distanceToEntitySquared(view));
            }
        });

        for (WorldRenderer wr : candidates) {
            if (room-- <= 0) {
                break;
            }
            UpdateTask task = task(wr);
            if (task.started || !wr.needsUpdate) {
                continue;
            }
            try {
                byte pad = 1;
                task.chunkCache = NeodymiumChunkCacheCompat.create(wr.worldObj,
                                                                    wr.posX - pad,
                                                                    wr.posY - pad,
                                                                    wr.posZ - pad,
                                                                    wr.posX + 16 + pad,
                                                                    wr.posY + 16 + pad,
                                                                    wr.posZ + 16 + pad,
                                                                    pad);
            } catch (Throwable t) {
                Share.log.debug("Could not snapshot renderer {} for worker build", wr, t);
                continue;
            }
            task.sortX = view.posX;
            task.sortY = view.posY;
            task.sortZ = view.posZ;
            task.cancelled = false;
            task.failure = null;
            task.finished = false;
            task.generation = generation;
            task.started = true;
            outstanding.incrementAndGet();
            workQueue.offer(wr);
        }
    }

    public void cancel(WorldRenderer wr) {
        UpdateTask task = task(wr);
        if (task.started) {
            task.cancelled = true;
        }
    }

    public static WorldRenderer currentMainRenderer() {
        return CURRENT_MAIN_RENDERER.get();
    }

    public static boolean shouldRenderOffThread(Block block) {
        try {
            int renderType = block.getRenderType();
            // 3.9.x deliberately kept render type 22 on the client thread; preserve that conservative rule.
            return renderType != 22 && !RUNTIME_UNSAFE_RENDER_TYPES.contains(renderType)
                    && IBlockExt.of(block).ft$isThreadSafe();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static UpdateTask task(WorldRenderer wr) {
        return ((IRendererUpdateTaskHolder) wr).ft$getNeodymiumUpdateTask();
    }

    private void runTask(WorldRenderer wr, long workerGeneration) {
        UpdateTask task = task(wr);
        ICapturableTessellator capture = null;
        ChunkCache cache = task.chunkCache;
        try {
            if (!task.started || task.generation != workerGeneration || task.cancelled) {
                return;
            }
            Tessellator tess = ThreadTessellator.getThreadTessellator();
            capture = (ICapturableTessellator) tess;
            capture.ft$discard();

            if (cache == null || cache.extendedLevelsInChunkCache()) {
                return;
            }

            NeodymiumChunkCacheCompat.renderStart(cache);
            RenderBlocks renderBlocks = new RenderBlocks(cache);
            for (int pass = 0; pass < 2 && !task.cancelled; pass++) {
                capture.ft$discard();
                ThreadedUpdateHooks.setWorldRenderPass(pass);
                ((PassTrackingTessellator) tess).pass(pass);
                int flags = buildPass(wr, task, cache, tess, pass, renderBlocks);
                Result result = task.result[pass];
                result.nextPass = (flags & 1) != 0;
                result.renderedSomething = (flags & 2) != 0;
                result.startedTessellator = (flags & 4) != 0;

                if (result.startedTessellator && !task.cancelled) {
                    if (pass == 1) {
                        result.vertexState = tess.getVertexState((float) task.sortX,
                                                                 (float) task.sortY,
                                                                 (float) task.sortZ);
                    } else {
                        result.vertexState = capture.ft$getUnsortedVertexState();
                    }
                    capture.ft$discard();
                }
            }
        } catch (Throwable t) {
            task.failure = t;
        } finally {
            ThreadedUpdateHooks.resetWorldRenderPass();
            if (cache != null) {
                try {
                    NeodymiumChunkCacheCompat.renderFinish(cache);
                } catch (Throwable ignored) {
                }
            }
            if (capture != null) {
                try {
                    capture.ft$discard();
                } catch (Throwable ignored) {
                }
            }
            task.finished = true;
            finishedQueue.offer(wr);
        }
    }

    private int buildPass(WorldRenderer wr,
                          UpdateTask task,
                          ChunkCache cache,
                          Tessellator tess,
                          int pass,
                          RenderBlocks renderBlocks) {
        int flags = 0;
        outer:
        for (int y = wr.posY; y < wr.posY + 16; y++) {
            for (int z = wr.posZ; z < wr.posZ + 16; z++) {
                for (int x = wr.posX; x < wr.posX + 16; x++) {
                    if (task.cancelled || !running || task.generation != generation) {
                        task.cancelled = true;
                        break outer;
                    }
                    Block block = cache.getBlock(x, y, z);
                    if (block == null || block.getMaterial() == Material.air || !shouldRenderOffThread(block)) {
                        continue;
                    }
                    if (block.getRenderBlockPass() > pass) {
                        flags |= 1;
                    }
                    if (!block.canRenderInPass(pass)) {
                        continue;
                    }
                    if ((flags & 4) == 0) {
                        flags |= 4;
                        tess.startDrawingQuads();
                        tess.setTranslation(-wr.posX, -wr.posY, -wr.posZ);
                    }
                    try {
                        if (renderBlocks.renderBlockByRenderType(block, x, y, z)) {
                            flags |= 2;
                        }
                    } catch (Throwable t) {
                        int renderType = block.getRenderType();
                        RUNTIME_UNSAFE_RENDER_TYPES.add(renderType);
                        task.failure = t;
                        task.cancelled = true;
                        if (WARNED_RENDER_TYPES.add(renderType)) {
                            Share.log.error("Exception while rendering renderType {} on a FalseTweaks worker. " +
                                            "Blacklisting this render type from worker builds and retrying it on the client thread.",
                                            renderType, t);
                        }
                        break outer;
                    }
                }
            }
        }
        return flags;
    }

    private void finishTask(UpdateTask task) {
        if (task.started && task.generation == generation) {
            outstanding.decrementAndGet();
        }
        task.clear();
    }

    private static void compact(List<WorldRenderer> list) {
        for (Iterator<WorldRenderer> it = list.iterator(); it.hasNext();) {
            WorldRenderer wr = it.next();
            if (wr == null || (!wr.needsUpdate && !task(wr).started)) {
                it.remove();
            }
        }
    }

    private static void removeIdentity(List<WorldRenderer> list, WorldRenderer target) {
        for (Iterator<WorldRenderer> it = list.iterator(); it.hasNext();) {
            if (it.next() == target) {
                it.remove();
            }
        }
    }

    private static boolean containsIdentity(List<WorldRenderer> list, WorldRenderer target) {
        for (WorldRenderer wr : list) {
            if (wr == target) {
                return true;
            }
        }
        return false;
    }

    public static final class Result {
        public boolean nextPass;
        public boolean startedTessellator;
        public boolean renderedSomething;
        public TesselatorVertexState vertexState;

        private void clear() {
            nextPass = false;
            startedTessellator = false;
            renderedSomething = false;
            vertexState = null;
        }
    }

    public static final class UpdateTask {
        public volatile boolean started;
        public volatile boolean cancelled;
        public volatile boolean finished;
        public volatile Throwable failure;
        public volatile long generation;
        public ChunkCache chunkCache;
        public double sortX;
        public double sortY;
        public double sortZ;
        public final Result[] result = new Result[]{new Result(), new Result()};

        public void clear() {
            chunkCache = null;
            failure = null;
            cancelled = false;
            finished = false;
            generation = 0L;
            for (Result r : result) {
                r.clear();
            }
            // Set this last: started=false publishes an entirely reset task to the main scheduler.
            started = false;
        }
    }

    private final class Worker extends Thread {
        private final long myGeneration;

        private Worker(String name, long myGeneration) {
            super(name);
            this.myGeneration = myGeneration;
        }

        @Override
        public void run() {
            while (running && myGeneration == generation) {
                try {
                    WorldRenderer wr = workQueue.take();
                    runTask(wr, myGeneration);
                } catch (InterruptedException e) {
                    if (!running || myGeneration != generation) {
                        return;
                    }
                } catch (Throwable t) {
                    Share.log.error("Unhandled exception in Neodymium chunk worker", t);
                }
            }
        }
    }
}
