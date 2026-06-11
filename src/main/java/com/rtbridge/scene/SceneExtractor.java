package com.rtbridge.scene;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.buffer.TripleBuffer;
import com.rtbridge.event.DirtyEvent;
import com.rtbridge.event.DirtyEventSystem;
import com.rtbridge.scene.cache.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SceneExtractor — spec §2.
 *
 * Converts Minecraft world state into RT-consumable cache entries.
 * Runs entirely event-driven — no per-frame world scans.
 *
 * Threading model:
 *   - DirtyEvent queue is drained on a single background thread.
 *   - Heavy mesh extraction (e.g. chunk re-tessellation) is submitted to
 *     a thread-pool executor.
 *   - Main thread ONLY provides the initial world snapshot reference.
 *   - Camera / world state is snapshotted at the start of each tick.
 */
public class SceneExtractor {

    private final DirtyEventSystem dirtyEvents;
    private final SceneDatabase    db;
    private final TripleBuffer     tripleBuffer;

    /** Single-threaded drain loop to preserve event ordering. */
    private final ExecutorService drainThread =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RTBridge-SceneExtractor");
            t.setDaemon(true);
            return t;
        });

    /** Pool for heavy mesh extraction work. */
    private final ExecutorService meshPool =
        Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2),
            r -> { Thread t = new Thread(r, "RTBridge-MeshWorker"); t.setDaemon(true); return t; }
        );

    public SceneExtractor(DirtyEventSystem dirtyEvents,
                          SceneDatabase db,
                          TripleBuffer tripleBuffer) {
        this.dirtyEvents  = dirtyEvents;
        this.db           = db;
        this.tripleBuffer = tripleBuffer;
    }

    // ── Per-tick entry point ──────────────────────────────────────────────────

    /**
     * Called each client tick.
     * Schedules a drain pass on the background thread.
     */
    public void tick() {
        drainThread.submit(this::drainEvents);
    }

    // ── Event drain ───────────────────────────────────────────────────────────

    private void drainEvents() {
        dirtyEvents.drainTo(this::handleEvent);
    }

    private void handleEvent(DirtyEvent event) {
        switch (event.type) {

            // ── Chunks ─────────────────────────────────────────────────────
            case CHUNK_LOAD   -> handleChunkLoad(event.chunkPos);
            case CHUNK_UNLOAD -> handleChunkUnload(event.chunkPos);
            case CHUNK_REMESH -> handleChunkRemesh(event.chunkPos);

            // ── Blocks ─────────────────────────────────────────────────────
            case BLOCK_PLACE,
                 BLOCK_BREAK         -> handleBlockChange(event.chunkPos);
            case BLOCK_ENTITY_UPDATE -> handleBlockEntityUpdate(event.blockPos);

            // ── Entities ───────────────────────────────────────────────────
            case ENTITY_SPAWN  -> handleEntitySpawn(event.entityId);
            case ENTITY_MOVE   -> handleEntityMove(event.entityId);   // TLAS only
            case ENTITY_REMOVE -> handleEntityRemove(event.entityId);

            // ── Ships ──────────────────────────────────────────────────────
            case SHIP_CREATE   -> handleShipCreate(event.shipId);
            case SHIP_MOVE     -> handleShipMove(event.shipId);        // TLAS only
            case SHIP_ROTATE   -> handleShipRotate(event.shipId);      // TLAS only
            case SHIP_DESTROY  -> handleShipDestroy(event.shipId);
            case SHIP_MODIFIED -> handleShipModified(event.shipId);   // BLAS rebuild

            // ── Lights ─────────────────────────────────────────────────────
            case LIGHT_ADD     -> handleLightAdd(event.blockPos);
            case LIGHT_REMOVE  -> handleLightRemove(event.blockPos);
            case LIGHT_UPDATE  -> handleLightUpdate(event.blockPos);

            default -> RTBridgeMod.LOGGER.warn("[SceneExtractor] Unhandled event: {}", event.type);
        }
    }

    // ── Chunk handlers ────────────────────────────────────────────────────────

    private void handleChunkLoad(ChunkPos pos) {
        if (pos == null) return;
        // Submit heavy tessellation to mesh pool; update Back buffer on completion
        meshPool.submit(() -> {
            // TODO: extract vertex data from chunk via Sodium/vanilla mesh
            long fakeBufHandle = pos.toLong(); // placeholder
            int  fakeVertCount = 0;

            SceneDatabase back = tripleBuffer.getBack();
            back.writeLock();
            try {
                back.staticGeometry().put(pos, fakeBufHandle, fakeVertCount);
            } finally {
                back.writeUnlock();
            }
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Chunk loaded: {}", pos);
        });
    }

    private void handleChunkUnload(ChunkPos pos) {
        if (pos == null) return;
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.staticGeometry().remove(pos);
        } finally {
            back.writeUnlock();
        }
    }

    private void handleChunkRemesh(ChunkPos pos) {
        handleChunkLoad(pos); // re-extract geometry
    }

    // ── Block handlers ────────────────────────────────────────────────────────

    /** A single block change dirties its chunk section. */
    private void handleBlockChange(ChunkPos chunkPos) {
        if (chunkPos != null) handleChunkRemesh(chunkPos);
    }

    private void handleBlockEntityUpdate(net.minecraft.util.math.BlockPos blockPos) {
        if (blockPos == null) return;
        // TODO: re-extract block entity mesh, update Dynamic cache
        RTBridgeMod.LOGGER.debug("[SceneExtractor] BlockEntity updated at {}", blockPos);
    }

    // ── Entity handlers ───────────────────────────────────────────────────────

    private void handleEntitySpawn(Long entityId) {
        if (entityId == null) return;
        meshPool.submit(() -> {
            // TODO: extract entity model mesh and upload to GPU
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Entity spawned: {}", entityId);
        });
    }

    /** MOVE → TLAS transform only, no BLAS rebuild. */
    private void handleEntityMove(Long entityId) {
        if (entityId == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        net.minecraft.entity.Entity entity = mc.world.getEntityById(entityId.intValue());
        if (entity == null) return;

        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.transformCache().updateTranslationRotation(
                entityId,
                new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ()),
                new Quaternionf().rotateY((float) Math.toRadians(entity.getYaw()))
            );
        } finally {
            back.writeUnlock();
        }
    }

    private void handleEntityRemove(Long entityId) {
        if (entityId == null) return;
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.dynamicGeometry().remove(entityId);
            back.transformCache().remove(entityId);
        } finally {
            back.writeUnlock();
        }
    }

    // ── Ship handlers ─────────────────────────────────────────────────────────

    /**
     * ShipCreate → async BLAS build.
     * Uses a placeholder AABB in the TLAS until the BLAS is ready (spec §7).
     */
    private void handleShipCreate(Long shipId) {
        if (shipId == null) return;
        RTBridgeMod.LOGGER.info("[SceneExtractor] Ship created: {}", shipId);

        // Register placeholder transform immediately so TLAS has an instance
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.transformCache().put(shipId,
                new Vector3f(0, 0, 0),
                new Quaternionf(),
                new Vector3f(1, 1, 1));
        } finally {
            back.writeUnlock();
        }

        // Heavy mesh work on pool thread → signal AsyncBLASBuilder when done
        meshPool.submit(() -> {
            // TODO: extract ship block mesh from VS2 ShipObject
            // RTBridgeMod.getRTRenderer().getBLASBuilder().submitShip(shipId, mesh);
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Ship mesh extraction queued: {}", shipId);
        });
    }

    /** MOVE/ROTATE → only update Transform Cache. Never touch BLAS. */
    private void handleShipMove(Long shipId) {
        updateShipTransform(shipId);
    }

    private void handleShipRotate(Long shipId) {
        updateShipTransform(shipId);
    }

    private void updateShipTransform(Long shipId) {
        if (shipId == null) return;
        // TODO: read actual transform from VS2 ShipObject
        // For now, no-op placeholder
        RTBridgeMod.LOGGER.debug("[SceneExtractor] Ship transform update: {}", shipId);
    }

    private void handleShipDestroy(Long shipId) {
        if (shipId == null) return;
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.dynamicGeometry().remove(shipId);
            back.transformCache().remove(shipId);
            back.emissiveCache().all()  // remove ship-associated lights
                .removeIf(e -> e.isOnShip() && e.shipId() == shipId);
        } finally {
            back.writeUnlock();
        }
        RTBridgeMod.LOGGER.info("[SceneExtractor] Ship destroyed: {}", shipId);
    }

    /** SHIP_MODIFIED (blocks added/removed) → queue BLAS rebuild. */
    private void handleShipModified(Long shipId) {
        if (shipId == null) return;
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.dynamicGeometry().markDirty(shipId);
        } finally {
            back.writeUnlock();
        }
        // Re-trigger mesh extraction
        meshPool.submit(() -> {
            // TODO: partial remesh of changed ship sections
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Ship BLAS rebuild queued: {}", shipId);
        });
    }

    // ── Light handlers ────────────────────────────────────────────────────────

    private void handleLightAdd(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        // TODO: read actual block light properties (color, intensity)
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.emissiveCache().add(
                pos,
                new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f),
                new Vector3f(1f, 0.9f, 0.7f), // warm white default
                1.0f,
                0.5f
            );
        } finally {
            back.writeUnlock();
        }
    }

    private void handleLightRemove(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        SceneDatabase back = tripleBuffer.getBack();
        back.writeLock();
        try {
            back.emissiveCache().removeByBlockPos(pos);
        } finally {
            back.writeUnlock();
        }
    }

    private void handleLightUpdate(net.minecraft.util.math.BlockPos pos) {
        // Simplest approach: remove + re-add
        handleLightRemove(pos);
        handleLightAdd(pos);
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdown() {
        drainThread.shutdown();
        meshPool.shutdown();
    }
}
