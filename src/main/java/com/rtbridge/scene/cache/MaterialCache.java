package com.rtbridge.scene.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * MaterialCache — spec §3 (Material Cache).
 *
 * Maps a block/mesh ID to its PBR material descriptor.
 * All texture references are indices into a GPU descriptor array.
 */
public class MaterialCache {

    public record MaterialEntry(
        int   materialId,
        int   albedoTexIdx,    // -1 = use default white
        int   normalTexIdx,    // -1 = flat normal
        int   metallicTexIdx,  // -1 = non-metallic
        int   roughnessTexIdx, // -1 = fully rough
        float baseMetallic,
        float baseRoughness,
        float transparency,    // 0 = opaque, 1 = fully transparent
        boolean emissive       // quick flag; real emission lives in EmissiveCache
    ) {}

    private final Map<Integer, MaterialEntry> materials = new HashMap<>();
    private int nextId = 1;

    // ── Mutators ──────────────────────────────────────────────────────────────

    public int register(int albedo, int normal, int metallic, int roughness,
                        float baseMetallic, float baseRoughness, float transparency) {
        int id = nextId++;
        materials.put(id, new MaterialEntry(id, albedo, normal, metallic, roughness,
            baseMetallic, baseRoughness, transparency, false));
        return id;
    }

    public void remove(int id) { materials.remove(id); }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public MaterialEntry get(int id) { return materials.get(id); }
    public int size()                { return materials.size(); }

    // ── Cache management ──────────────────────────────────────────────────────

    public void copyFrom(MaterialCache src) {
        this.materials.clear();
        this.materials.putAll(src.materials);
        this.nextId = src.nextId;
    }

    public void clear() { materials.clear(); nextId = 1; }
}
