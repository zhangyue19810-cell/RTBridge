package com.rtbridge.event;

import com.rtbridge.RTBridgeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central dirty-event bus.
 *
 * Design rules (from spec §1):
 *   - NEVER poll the entire world each frame.
 *   - Only fire events when something actually changes.
 *   - Listeners are processed asynchronously by SceneExtractor.
 *   - Ship MOVE/ROTATE → only TLAS transform update, never BLAS rebuild.
 */
public class DirtyEventSystem {

    /** Thread-safe queue: main thread pushes, extractor thread drains. */
    private final ConcurrentLinkedQueue<DirtyEvent> queue = new ConcurrentLinkedQueue<>();

    /** Registered listeners (typically just SceneExtractor). */
    private final List<DirtyEventListener> listeners = new ArrayList<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public void post(DirtyEvent event) {
        queue.add(event);
    }

    /** Called every tick by the extractor thread to drain queued events. */
    public void drainTo(DirtyEventListener consumer) {
        DirtyEvent ev;
        while ((ev = queue.poll()) != null) {
            consumer.onDirtyEvent(ev);
        }
    }

    public void addListener(DirtyEventListener listener) {
        listeners.add(listener);
    }

    // ── Fabric hook registration ──────────────────────────────────────────────

    /**
     * Wire up all the Fabric API callbacks that translate MC world events into
     * DirtyEvent objects.  Called once at mod init.
     */
    public void registerNeoForgeHooks() {
        registerChunkHooks();
        registerBlockHooks();
        // Entity hooks are registered via mixin — see MixinWorldRenderer
        // Ship hooks are registered via VS2 API — see ValkyrienSkiesCompat
        RTBridgeMod.LOGGER.debug("[RTBridge] DirtyEventSystem hooks registered");
    }

    // ── Chunk ─────────────────────────────────────────────────────────────────

    private void registerChunkHooks() {
        // TODO: NeoForge ChunkEvent.Load  -> post CHUNK_LOAD
        // TODO: NeoForge ChunkEvent.Unload -> post CHUNK_UNLOAD
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    private void registerBlockHooks() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) ->
            post(DirtyEvent.of(DirtyEventType.BLOCK_BREAK)
                .block(pos)
                .chunk(new net.minecraft.util.math.ChunkPos(pos))
                .build())
        );

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // UseBlock may place a block or interact with a BlockEntity; mark dirty
            post(DirtyEvent.of(DirtyEventType.BLOCK_ENTITY_UPDATE)
                .block(hitResult.getBlockPos())
                .build());
            return net.minecraft.util.ActionResult.PASS;
        });
    }

    // ── Ship helpers (called by ValkyrienSkiesCompat) ─────────────────────────

    public void postShipCreate(long shipId) {
        post(DirtyEvent.of(DirtyEventType.SHIP_CREATE).ship(shipId).build());
    }

    public void postShipMove(long shipId) {
        // MOVE only → TLAS transform, never BLAS
        post(DirtyEvent.of(DirtyEventType.SHIP_MOVE).ship(shipId).build());
    }

    public void postShipRotate(long shipId) {
        post(DirtyEvent.of(DirtyEventType.SHIP_ROTATE).ship(shipId).build());
    }

    public void postShipDestroy(long shipId) {
        post(DirtyEvent.of(DirtyEventType.SHIP_DESTROY).ship(shipId).build());
    }

    public void postShipModified(long shipId) {
        post(DirtyEvent.of(DirtyEventType.SHIP_MODIFIED).ship(shipId).build());
    }

    // ── Light helpers (called by EmissiveCache or Sable compat) ──────────────

    public void postLightAdd(net.minecraft.util.math.BlockPos pos) {
        post(DirtyEvent.of(DirtyEventType.LIGHT_ADD).block(pos).build());
    }

    public void postLightRemove(net.minecraft.util.math.BlockPos pos) {
        post(DirtyEvent.of(DirtyEventType.LIGHT_REMOVE).block(pos).build());
    }

    public void postLightUpdate(net.minecraft.util.math.BlockPos pos) {
        post(DirtyEvent.of(DirtyEventType.LIGHT_UPDATE).block(pos).build());
    }
}
