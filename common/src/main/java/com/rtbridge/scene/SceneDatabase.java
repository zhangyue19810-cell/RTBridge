package com.rtbridge.scene;

import com.rtbridge.scene.cache.*;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Scene Database — spec §3.
 *
 * The single source of truth consumed by both the OpenGL and RT branches.
 * Holds five caches:
 *
 *   StaticGeometryCache   — terrain, chunk mesh, static structures
 *   DynamicGeometryCache  — entities, block entities, Create machinery
 *   TransformCache        — ship/instance matrices (position, rotation, scale)
 *   MaterialCache         — albedo, metallic, roughness, normal, transparency
 *   EmissiveCache         — light-emitting geometry (replaces flat 0-15 MC light)
 *
 * Concurrency: a ReadWriteLock protects the whole database.
 * The TripleBuffer wraps THREE SceneDatabase copies so readers and writers
 * never block each other across frames.
 */
public class SceneDatabase {

    // ── Caches ────────────────────────────────────────────────────────────────
    private final StaticGeometryCache  staticGeometry  = new StaticGeometryCache();
    private final DynamicGeometryCache dynamicGeometry = new DynamicGeometryCache();
    private final TransformCache       transformCache  = new TransformCache();
    private final MaterialCache        materialCache   = new MaterialCache();
    private final EmissiveCache        emissiveCache   = new EmissiveCache();

    /** Guards bulk-snapshot operations (e.g. triple-buffer copy). */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public StaticGeometryCache  staticGeometry()  { return staticGeometry; }
    public DynamicGeometryCache dynamicGeometry() { return dynamicGeometry; }
    public TransformCache       transformCache()  { return transformCache; }
    public MaterialCache        materialCache()   { return materialCache; }
    public EmissiveCache        emissiveCache()   { return emissiveCache; }

    // ── Lock helpers ──────────────────────────────────────────────────────────

    public void readLock()    { lock.readLock().lock();     }
    public void readUnlock()  { lock.readLock().unlock();   }
    public void writeLock()   { lock.writeLock().lock();    }
    public void writeUnlock() { lock.writeLock().unlock();  }

    /**
     * Shallow-copy all caches from {@code src} into this database.
     * Called by TripleBuffer during the Back→Middle rotation.
     * Must be called while holding writeLock on this and readLock on src.
     */
    public void copyFrom(SceneDatabase src) {
        this.staticGeometry .copyFrom(src.staticGeometry);
        this.dynamicGeometry.copyFrom(src.dynamicGeometry);
        this.transformCache .copyFrom(src.transformCache);
        this.materialCache  .copyFrom(src.materialCache);
        this.emissiveCache  .copyFrom(src.emissiveCache);
    }

    /** Reset all caches (used during world unload). */
    public void clear() {
        writeLock();
        try {
            staticGeometry .clear();
            dynamicGeometry.clear();
            transformCache .clear();
            materialCache  .clear();
            emissiveCache  .clear();
        } finally {
            writeUnlock();
        }
    }
}
