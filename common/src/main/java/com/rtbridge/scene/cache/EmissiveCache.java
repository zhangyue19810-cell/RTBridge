package com.rtbridge.scene.cache;

import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

import java.util.*;

/**
 * EmissiveCache — spec §3 (Emissive Cache) and §8 (Light Sampling).
 *
 * Replaces Minecraft's flat 0-15 light level with proper emissive geometry
 * entries that the RT light sampler can use for:
 *   - Light clustering (1st layer)
 *   - Reservoir sampling (2nd layer)
 *   - Temporal / spatial reuse (3rd / 4th layer)
 *
 * Sources:
 *   - Vanilla emissive blocks (glowstone, sea lantern, etc.)
 *   - Sable light blocks
 *   - Create custom emissive contraptions
 *   - Ship interior lighting
 */
public class EmissiveCache {

    /**
     * A single emissive source — either a surface patch (emissive geometry)
     * or a punctual/spherical area light.
     */
    public record EmissiveEntry(
        long      id,          // unique stable ID across frames
        BlockPos  blockPos,    // world-space block position (null for entity lights)
        Vector3f  worldPos,    // exact world-space center (float precision for RT)
        Vector3f  color,       // linear RGB radiance
        float     intensity,   // power multiplier
        float     radius,      // sphere radius; 0 = punctual
        boolean   isOnShip,    // true → position is in ship-local space
        long      shipId       // valid when isOnShip
    ) {}

    private final Map<Long, EmissiveEntry> entries = new LinkedHashMap<>();
    private long nextId = 1L;

    // ── Mutators ──────────────────────────────────────────────────────────────

    public long add(BlockPos blockPos, Vector3f worldPos, Vector3f color,
                    float intensity, float radius) {
        long id = nextId++;
        entries.put(id, new EmissiveEntry(id, blockPos, worldPos, color,
            intensity, radius, false, 0L));
        return id;
    }

    public long addShipLight(BlockPos localPos, Vector3f localCenter, Vector3f color,
                             float intensity, float radius, long shipId) {
        long id = nextId++;
        entries.put(id, new EmissiveEntry(id, localPos, localCenter, color,
            intensity, radius, true, shipId));
        return id;
    }

    public void remove(long id) {
        entries.remove(id);
    }

    public void removeByBlockPos(BlockPos pos) {
        entries.values().removeIf(e -> pos.equals(e.blockPos()));
    }

    public void updateIntensity(long id, float newIntensity) {
        EmissiveEntry e = entries.get(id);
        if (e != null) {
            entries.put(id, new EmissiveEntry(e.id(), e.blockPos(), e.worldPos(),
                e.color(), newIntensity, e.radius(), e.isOnShip(), e.shipId()));
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public EmissiveEntry       get(long id)         { return entries.get(id); }
    public Collection<EmissiveEntry> all()          { return entries.values(); }
    public int                 size()               { return entries.size(); }

    /**
     * Return all entries whose world position falls within {@code radius} of
     * {@code center}.  Used by the light clustering stage.
     */
    public List<EmissiveEntry> queryRadius(Vector3f center, float radius) {
        float r2 = radius * radius;
        List<EmissiveEntry> result = new ArrayList<>();
        for (EmissiveEntry e : entries.values()) {
            float dx = e.worldPos().x - center.x;
            float dy = e.worldPos().y - center.y;
            float dz = e.worldPos().z - center.z;
            if (dx*dx + dy*dy + dz*dz <= r2) result.add(e);
        }
        return result;
    }

    // ── Cache management ──────────────────────────────────────────────────────

    public void copyFrom(EmissiveCache src) {
        this.entries.clear();
        this.entries.putAll(src.entries);
        this.nextId = src.nextId;
    }

    public void clear() {
        entries.clear();
        nextId = 1L;
    }
}
