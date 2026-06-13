package com.rtbridge.scene.cache;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * TransformCache — spec §3 (Transform Cache).
 *
 * Stores per-ship / per-dynamic-instance transforms used to update
 * TLAS Instance Transforms without touching BLAS geometry.
 *
 * Key: shipId (Long) or entityId (Long, negated to distinguish)
 * Value: ShipTransform record
 *
 * Design rule: MOVE/ROTATE events → update this cache only.
 *              Never rebuild BLAS on a simple transform change.
 */
public class TransformCache {

    public record ShipTransform(
        long       id,
        Vector3f   position,
        Quaternionf rotation,
        Vector3f   scale,
        Matrix4f   worldMatrix   // pre-computed: T * R * S
    ) {
        /** Reconstruct worldMatrix from position/rotation/scale. */
        public static ShipTransform of(long id, Vector3f pos, Quaternionf rot, Vector3f scale) {
            Matrix4f m = new Matrix4f()
                .translate(pos)
                .rotate(rot)
                .scale(scale);
            return new ShipTransform(id, pos, rot, scale, m);
        }
    }

    private final Map<Long, ShipTransform> transforms = new HashMap<>();

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void put(long id, Vector3f pos, Quaternionf rot, Vector3f scale) {
        transforms.put(id, ShipTransform.of(id, pos, rot, scale));
    }

    /** Update only position + rotation (no scale change). */
    public void updateTranslationRotation(long id, Vector3f pos, Quaternionf rot) {
        ShipTransform existing = transforms.get(id);
        Vector3f scale = existing != null ? existing.scale() : new Vector3f(1f);
        transforms.put(id, ShipTransform.of(id, pos, rot, scale));
    }

    public void remove(long id) {
        transforms.remove(id);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ShipTransform get(long id)              { return transforms.get(id); }
    public boolean       contains(long id)         { return transforms.containsKey(id); }
    public Set<Long>     allIds()                  { return transforms.keySet(); }
    public int           size()                    { return transforms.size(); }

    // ── Cache management ──────────────────────────────────────────────────────

    public void copyFrom(TransformCache src) {
        this.transforms.clear();
        this.transforms.putAll(src.transforms);
    }

    public void clear() {
        transforms.clear();
    }
}
