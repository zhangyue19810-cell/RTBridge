package com.rtbridge.bvh;

import org.joml.Matrix4f;

import java.util.*;

/**
 * TLASInstanceBuffer — spec §7 (TLAS / BLAS Strategy).
 *
 * Maintains the list of VkAccelerationStructureInstanceKHR-equivalent records.
 * On each RT frame:
 *   1. Any dirty transform entries are patched in-place (no TLAS rebuild needed
 *      when geometry doesn't change, only transforms).
 *   2. New/removed BLAS entries trigger a full TLAS rebuild (but BLAS is reused).
 *
 * Key invariant from spec:
 *   ShipMove / ShipRotate → update Instance Transform only.
 *   ShipCreate / ShipDestroy / ShipModified → structural TLAS rebuild.
 */
public class TLASInstanceBuffer {

    // ── Instance record ───────────────────────────────────────────────────────

    public record TLASInstance(
        long     ownerId,
        int      blasHandle,
        int      instanceCustomIndex,   // maps to material / mesh ID in shaders
        int      mask,                  // ray mask (0xFF = visible to all ray types)
        int      shaderBindingOffset,
        Matrix4f transform,             // row-major 3×4 used by Vulkan RT
        boolean  transformDirty         // true = needs upload but no structural change
    ) {}

    // ── State ─────────────────────────────────────────────────────────────────

    private final LinkedHashMap<Long, TLASInstance> instances = new LinkedHashMap<>();
    private boolean structuralDirty = false; // full TLAS rebuild needed
    private boolean anyTransformDirty = false;

    // ── Mutators ──────────────────────────────────────────────────────────────

    /**
     * Add a new instance after BLAS is ready (ShipCreate / ChunkLoad).
     * Marks structural dirty — a full TLAS rebuild will be triggered next frame.
     */
    public void addInstance(long ownerId, int blasHandle, Matrix4f transform) {
        int idx = instances.size();
        instances.put(ownerId, new TLASInstance(
            ownerId, blasHandle, idx, 0xFF, 0, transform, false));
        structuralDirty = true;
    }

    /**
     * Remove an instance (ShipDestroy / ChunkUnload).
     */
    public void removeInstance(long ownerId) {
        if (instances.remove(ownerId) != null) {
            structuralDirty = true;
        }
    }

    /**
     * Update only the transform of an existing instance.
     * Does NOT trigger a TLAS rebuild — only a cheap transform buffer patch.
     * Called for ShipMove / ShipRotate / EntityMove.
     */
    public void updateTransform(long ownerId, Matrix4f newTransform) {
        TLASInstance existing = instances.get(ownerId);
        if (existing == null) return;
        instances.put(ownerId, new TLASInstance(
            existing.ownerId(),
            existing.blasHandle(),
            existing.instanceCustomIndex(),
            existing.mask(),
            existing.shaderBindingOffset(),
            newTransform,
            true   // transform-only dirty
        ));
        anyTransformDirty = true;
    }

    /**
     * Swap in a newly-built BLAS for an existing instance (async build complete).
     * Triggers structural rebuild since the BLAS handle changed.
     */
    public void swapBLAS(long ownerId, int newBlasHandle) {
        TLASInstance existing = instances.get(ownerId);
        if (existing == null) return;
        instances.put(ownerId, new TLASInstance(
            existing.ownerId(), newBlasHandle,
            existing.instanceCustomIndex(), existing.mask(),
            existing.shaderBindingOffset(), existing.transform(), false));
        structuralDirty = true;
    }

    // ── Frame sync ────────────────────────────────────────────────────────────

    public boolean needsStructuralRebuild() { return structuralDirty; }
    public boolean needsTransformUpload()   { return anyTransformDirty; }

    /** Call after uploading to GPU to clear dirty flags. */
    public void clearDirtyFlags() {
        structuralDirty   = false;
        anyTransformDirty = false;
        // Clear per-instance transform dirty flags
        instances.replaceAll((id, inst) -> inst.transformDirty()
            ? new TLASInstance(inst.ownerId(), inst.blasHandle(),
                inst.instanceCustomIndex(), inst.mask(),
                inst.shaderBindingOffset(), inst.transform(), false)
            : inst);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Collection<TLASInstance> all()       { return instances.values(); }
    public TLASInstance             get(long id){ return instances.get(id); }
    public int                      size()      { return instances.size(); }

    public List<TLASInstance> dirtyTransforms() {
        List<TLASInstance> result = new ArrayList<>();
        for (TLASInstance inst : instances.values()) {
            if (inst.transformDirty()) result.add(inst);
        }
        return result;
    }
}
