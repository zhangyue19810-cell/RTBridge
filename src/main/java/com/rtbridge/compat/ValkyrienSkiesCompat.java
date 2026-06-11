package com.rtbridge.compat;

import com.rtbridge.RTBridgeMod;
import com.rtbridge.bvh.TLASInstanceBuffer;
import com.rtbridge.scene.SceneDatabase;
import com.rtbridge.scene.cache.TransformCache;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * ValkyrienSkiesCompat — spec §11 (Aeronautics Compatibility Strategy).
 *
 * Bridges the Valkyrien Skies 2 / Aeronautics ship system with RTBridge.
 *
 * Rules from spec:
 *   ShipCreate   → async BLAS build + placeholder AABB in TLAS
 *   ShipMove     → TLAS Instance Transform only   (NO BLAS rebuild)
 *   ShipRotate   → TLAS Instance Transform only   (NO BLAS rebuild)
 *   ShipModified → queue async BLAS rebuild       (blocks added/removed)
 *   ShipDestroy  → remove BLAS + TLAS instance
 *
 * Dynamic lights on ships go into EmissiveCache with isOnShip=true.
 * Their world position must be recomputed each frame via the ship's
 * TransformCache entry (localPos → worldPos using ship world matrix).
 *
 * Loading strategy:
 *   This class is only instantiated when VS2 is present.
 *   Use FabricLoader.getInstance().isModLoaded("valkyrienskies") before calling.
 *
 * TODO: Replace all stub ship data reads with actual VS2 API calls once
 *       the VS2 Fabric API is properly set up in the project.
 */
public class ValkyrienSkiesCompat {

    private final TLASInstanceBuffer tlasBuffer;

    public ValkyrienSkiesCompat(TLASInstanceBuffer tlasBuffer) {
        this.tlasBuffer = tlasBuffer;
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Register VS2 ship event listeners.
     * Call once at mod init, after confirming VS2 is loaded.
     *
     * TODO: Replace with actual VS2 event API:
     *   ShipLoadedEvent.EVENT.register(...)
     *   ShipUnloadedEvent.EVENT.register(...)
     *   etc.
     */
    public void registerShipEvents() {
        RTBridgeMod.LOGGER.info("[RTBridge] Registering Valkyrien Skies ship events.");

        // Example hookup (pseudocode — replace with real VS2 event API):
        //
        // VSGameEvents.SHIP_LOADED.register((world, ship) -> {
        //     long shipId = ship.getId();
        //     RTBridgeMod.getDirtyEventSystem().postShipCreate(shipId);
        //     submitInitialShipBLAS(shipId, ship);
        // });
        //
        // VSGameEvents.SHIP_PRE_TICK.register((world, ship) -> {
        //     onShipTransformChanged(ship.getId(),
        //         ship.getShipToWorld().getPosition(),
        //         ship.getShipToWorld().getRotation());
        // });
        //
        // VSGameEvents.SHIP_UNLOADED.register((world, ship) -> {
        //     RTBridgeMod.getDirtyEventSystem().postShipDestroy(ship.getId());
        // });
    }

    // ── Ship lifecycle handlers ───────────────────────────────────────────────

    /**
     * Called when a ship is first created / loaded.
     * Registers a placeholder AABB transform in the TLAS immediately,
     * then queues an async BLAS build.
     */
    public void onShipCreated(long shipId, Vector3f initialPos, Quaternionf initialRot) {
        RTBridgeMod.LOGGER.info("[VS2Compat] Ship created: id={}", shipId);

        // 1. Register an identity placeholder in the TLAS so RT can start
        //    using the ship (as an AABB) before the BLAS is ready.
        Matrix4f placeholderTransform = buildTransformMatrix(initialPos, initialRot, new Vector3f(1f));
        tlasBuffer.addInstance(shipId, -1 /* placeholder BLAS */, placeholderTransform);

        // 2. Post event so SceneExtractor queues the mesh extraction + BLAS build
        RTBridgeMod.getDirtyEventSystem().postShipCreate(shipId);

        // 3. Submit placeholder BLAS immediately so TLAS has something valid
        RTBridgeMod.getRTRenderer().getBLASBuilder().submitPlaceholder(
            shipId,
            (ownerId, blasHandle) -> {
                tlasBuffer.swapBLAS(ownerId, blasHandle);
                RTBridgeMod.LOGGER.debug("[VS2Compat] Placeholder BLAS {} for ship {}", blasHandle, ownerId);
            }
        );
    }

    /**
     * Called every tick when a ship has moved or rotated.
     * ONLY updates the TLAS Instance Transform — never touches the BLAS.
     */
    public void onShipTransformChanged(long shipId, Vector3f worldPos, Quaternionf worldRot) {
        Matrix4f m = buildTransformMatrix(worldPos, worldRot, new Vector3f(1f));
        tlasBuffer.updateTransform(shipId, m);
        RTBridgeMod.getDirtyEventSystem().postShipMove(shipId);
    }

    /**
     * Called when blocks are added/removed on a ship.
     * Queues an async BLAS rebuild; TLAS reuses the old BLAS until the new one is ready.
     */
    public void onShipModified(long shipId) {
        RTBridgeMod.getDirtyEventSystem().postShipModified(shipId);

        // TODO: extract updated ship vertex data and submit real BLAS build:
        // float[] vertices = extractShipVertices(shipId);
        // int[]   indices  = extractShipIndices(shipId);
        // RTBridgeMod.getRTRenderer().getBLASBuilder().submitShip(
        //     shipId, vertices, indices,
        //     (ownerId, blasHandle) -> tlasBuffer.swapBLAS(ownerId, blasHandle)
        // );
    }

    /**
     * Called when a ship is destroyed or unloaded.
     */
    public void onShipDestroyed(long shipId) {
        tlasBuffer.removeInstance(shipId);
        RTBridgeMod.getDirtyEventSystem().postShipDestroy(shipId);
        RTBridgeMod.LOGGER.info("[VS2Compat] Ship removed from TLAS: id={}", shipId);
    }

    // ── Transform lights on ships ─────────────────────────────────────────────

    /**
     * Transform ship-local emissive positions to world space using the
     * current TransformCache entry.  Must be called each frame for moving ships.
     *
     * @param db the Back SceneDatabase being prepared for this frame
     */
    public void updateShipLightWorldPositions(long shipId, SceneDatabase db) {
        TransformCache.ShipTransform t = db.transformCache().get(shipId);
        if (t == null) return;

        db.emissiveCache().all().stream()
            .filter(e -> e.isOnShip() && e.shipId() == shipId)
            .forEach(e -> {
                // Transform local light pos → world pos using ship's world matrix
                org.joml.Vector4f worldPos4 = new org.joml.Vector4f(
                    e.worldPos().x, e.worldPos().y, e.worldPos().z, 1f)
                    .mul(t.worldMatrix());
                Vector3f worldPos = new Vector3f(worldPos4.x, worldPos4.y, worldPos4.z);
                // TODO: update world position in EmissiveCache entry
                // (currently EmissiveEntry is a record — needs mutable variant or re-add)
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Matrix4f buildTransformMatrix(Vector3f pos, Quaternionf rot, Vector3f scale) {
        return new Matrix4f().translate(pos).rotate(rot).scale(scale);
    }

    // ── Availability check ────────────────────────────────────────────────────

    public static boolean isLoaded() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
            .isModLoaded("valkyrienskies");
    }
}
