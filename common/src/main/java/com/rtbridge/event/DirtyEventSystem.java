package com.rtbridge.event;

import com.rtbridge.RTBridgeMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * DirtyEventSystem — 平台无关事件总线。
 * Fabric/NeoForge 平台特定钩子在各自入口注册。
 */
public class DirtyEventSystem {

    private final ConcurrentLinkedQueue<DirtyEvent> queue = new ConcurrentLinkedQueue<>();

    public void post(DirtyEvent event) { queue.add(event); }

    public void drainTo(DirtyEventListener consumer) {
        DirtyEvent ev;
        while ((ev = queue.poll()) != null) consumer.onDirtyEvent(ev);
    }

    // ── 平台钩子注册（子类/平台入口覆盖）────────────────────────────────────
    public void registerFabricHooks() {
        // 由 RTBridgeFabric 调用后注册 Fabric 事件
        RTBridgeMod.LOGGER.debug("[DirtyEventSystem] Fabric hooks registered");
    }

    // ── Ship helpers ──────────────────────────────────────────────────────────
    public void postShipCreate(long id)  { post(DirtyEvent.of(DirtyEventType.SHIP_CREATE).ship(id).build()); }
    public void postShipMove(long id)    { post(DirtyEvent.of(DirtyEventType.SHIP_MOVE).ship(id).build()); }
    public void postShipRotate(long id)  { post(DirtyEvent.of(DirtyEventType.SHIP_ROTATE).ship(id).build()); }
    public void postShipDestroy(long id) { post(DirtyEvent.of(DirtyEventType.SHIP_DESTROY).ship(id).build()); }
    public void postShipModified(long id){ post(DirtyEvent.of(DirtyEventType.SHIP_MODIFIED).ship(id).build()); }

    // ── Light helpers ─────────────────────────────────────────────────────────
    public void postLightAdd(BlockPos pos)    { post(DirtyEvent.of(DirtyEventType.LIGHT_ADD).block(pos).build()); }
    public void postLightRemove(BlockPos pos) { post(DirtyEvent.of(DirtyEventType.LIGHT_REMOVE).block(pos).build()); }
    public void postLightUpdate(BlockPos pos) { post(DirtyEvent.of(DirtyEventType.LIGHT_UPDATE).block(pos).build()); }
}
