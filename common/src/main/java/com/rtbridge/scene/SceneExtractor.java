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

    // ── 临时测试场景：用硬编码顶点验证 RT 链路 ──────────────────────────────────
    // 一个 8x8x8 的立方体，放在世界原点附近
    // 每个三角形: 3 x vec3 = 9 floats = 36 bytes; 12 个三角形组成一个立方体
    private static final float[] TEST_CUBE_VERTS;
    static {
        float s = 4f; // 半边长 4 格
        TEST_CUBE_VERTS = new float[]{
            // -X face
            -s,-s,-s,  -s,-s, s,  -s, s, s,
            -s,-s,-s,  -s, s, s,  -s, s,-s,
            // +X face
             s,-s,-s,   s, s, s,   s,-s, s,
             s,-s,-s,   s, s,-s,   s, s, s,
            // -Y face
            -s,-s,-s,   s,-s,-s,   s,-s, s,
            -s,-s,-s,   s,-s, s,  -s,-s, s,
            // +Y face
            -s, s,-s,   s, s, s,   s, s,-s,
            -s, s,-s,  -s, s, s,   s, s, s,
            // -Z face
            -s,-s,-s,  -s, s,-s,   s, s,-s,
            -s,-s,-s,   s, s,-s,   s,-s,-s,
            // +Z face
            -s,-s, s,   s,-s, s,   s, s, s,
            -s,-s, s,   s, s, s,  -s, s, s,
        };
    }

    private static volatile long testBLASHandle = 0L;
    private static volatile boolean testBLASBuilt = false;

    private void handleChunkLoad(ChunkPos pos) {
        if (pos == null) return;
        meshPool.submit(() -> {
            // 如果还没有测试场景，建一个临时 BLAS（验证 RT 链路）
            if (!testBLASBuilt) {
                synchronized (SceneExtractor.class) {
                    if (!testBLASBuilt) {
                        buildTestBLAS();
                        testBLASBuilt = true;
                    }
                }
            }

            SceneDatabase back = tripleBuffer.getBack();
            back.writeLock();
            try {
                // 用测试 BLAS 句柄（非零）填进去，让 TLAS 有内容
                if (testBLASHandle != 0L) {
                    back.staticGeometry().put(pos, testBLASHandle, TEST_CUBE_VERTS.length / 3);
                } else {
                    back.staticGeometry().put(pos, pos.toLong(), 0);
                }
            } finally {
                back.writeUnlock();
            }
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Chunk loaded: {} blasHandle=0x{}",
                pos, Long.toHexString(testBLASHandle));
        });
    }


    /** 公开入口：确保测试场景已提交（从外部调用触发） */
    public void ensureTestScene() {
        if (!testBLASBuilt) {
            synchronized (SceneExtractor.class) {
                if (!testBLASBuilt) {
                    testBLASBuilt = true;
                    buildTestBLAS();
                }
            }
        }
    }

    /** 构建测试 BLAS，用已有的 submitChunk 接口验证 RT 链路 */
    private void buildTestBLAS() {
        try {
            var rt = RTBridgeMod.getRTRenderer();
            if (rt == null) { RTBridgeMod.LOGGER.warn("[SceneExtractor] RTRenderer 未就绪"); return; }
            var blasBuilder = rt.getBLASBuilder();
            if (blasBuilder == null) { RTBridgeMod.LOGGER.warn("[SceneExtractor] BLASBuilder 未就绪"); return; }

            RTBridgeMod.LOGGER.info("[SceneExtractor] 提交测试 BLAS {} verts...", TEST_CUBE_VERTS.length / 3);
            int[] idx = new int[TEST_CUBE_VERTS.length / 3];
            for (int i = 0; i < idx.length; i++) idx[i] = i;

            long testKey = Long.MIN_VALUE;
            blasBuilder.submitChunk(testKey, TEST_CUBE_VERTS, idx, (ownerId, entry) -> {
                testBLASHandle = entry.asHandle;
                RTBridgeMod.LOGGER.info("[SceneExtractor] 测试 BLAS 就绪: asHandle=0x{} devAddr=0x{}",
                    Long.toHexString(entry.asHandle), Long.toHexString(entry.deviceAddress));
                var tlas = rt.getTLASManager();
                if (tlas != null) {
                    try {
                        tlas.addInstance(testKey, entry, new org.joml.Matrix4f());
                        tlas.rebuild();
                        RTBridgeMod.LOGGER.info("[SceneExtractor] TLAS rebuilt! handle=0x{}",
                            Long.toHexString(tlas.getTLASHandle()));
                    } catch (Throwable e2) {
                        RTBridgeMod.LOGGER.error("[SceneExtractor] TLAS rebuild 失败: {}", e2.getMessage(), e2);
                    }
                }
            });
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[SceneExtractor] 测试 BLAS 提交失败: {}", e.getMessage(), e);
        }
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
