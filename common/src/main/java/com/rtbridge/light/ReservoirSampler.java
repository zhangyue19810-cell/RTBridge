package com.rtbridge.light;

import com.rtbridge.scene.cache.EmissiveCache;
import org.joml.Vector3f;

import java.util.*;

/**
 * ReservoirSampler — spec §8, Layers 2-4: Reservoir / Temporal / Spatial Reuse.
 *
 * CPU-side prototype of RTXDI-style reservoir sampling.
 * In production this logic moves to a compute shader; the Java implementation
 * serves as a reference and for headless testing.
 *
 * Algorithm (per pixel / per sample):
 *   1. Fetch candidate light list from LightCluster (layer 1).
 *   2. Run weighted reservoir sampling (WRS) over candidates using
 *      a target PDF proportional to power × solid angle.
 *   3. Store selected sample + weight in the Reservoir.
 *   4. Temporal reuse: merge current reservoir with reservoir from previous frame
 *      at the same pixel (reprojected via motion vector).
 *   5. Spatial reuse: merge reservoirs from N random neighbour pixels.
 *
 * References:
 *   - Bitterli et al. 2020, "Spatiotemporal reservoir resampling for real-time
 *     ray tracing with dynamic direct illumination" (ReSTIR DI)
 *   - RTXDI SDK (NVIDIA)
 */
public class ReservoirSampler {

    // ── Reservoir ─────────────────────────────────────────────────────────────

    /** Per-pixel reservoir. Stores the currently selected light sample. */
    public static final class Reservoir {
        public long  selectedLightId = -1L;
        public float wSum  = 0f; // sum of sample weights seen so far
        public int   M     = 0;  // number of candidates seen
        public float W     = 0f; // unbiased contribution weight = wSum / (M * p_hat)

        /** Streaming update — merge one new sample using WRS. */
        public boolean update(long lightId, float weight, float rand) {
            wSum += weight;
            M++;
            if (rand < weight / wSum) {
                selectedLightId = lightId;
                return true;
            }
            return false;
        }

        /** Merge another reservoir into this one (temporal / spatial reuse). */
        public void merge(Reservoir other, float targetPdf, float rand) {
            float w = other.W * targetPdf * other.M;
            wSum += w;
            M    += other.M;
            if (wSum > 0f && rand < w / wSum) {
                selectedLightId = other.selectedLightId;
            }
        }

        public void finalise(float targetPdf) {
            W = (targetPdf > 0f) ? wSum / (M * targetPdf) : 0f;
        }

        public void reset() {
            selectedLightId = -1L;
            wSum = 0f;
            M    = 0;
            W    = 0f;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final LightCluster cluster;
    private final Random        rng     = new Random();

    // Previous frame's reservoirs for temporal reuse — indexed [pixelY][pixelX]
    private Reservoir[][] prevReservoirs;

    public ReservoirSampler(LightCluster cluster) {
        this.cluster = cluster;
    }

    // ── Per-pixel sampling ────────────────────────────────────────────────────

    /**
     * Sample one light for a surface hit point.
     *
     * @param worldPos      surface position in world space
     * @param surfaceNormal shading normal
     * @param emissives     reference to EmissiveCache for light data
     * @return the selected EmissiveEntry, or null if no lights in range
     */
    public EmissiveCache.EmissiveEntry sample(
            Vector3f worldPos,
            Vector3f surfaceNormal,
            EmissiveCache emissives) {

        List<Long> candidates = cluster.getCandidates(worldPos);
        if (candidates.isEmpty()) return null;

        Reservoir r = new Reservoir();
        for (long id : candidates) {
            EmissiveCache.EmissiveEntry light = emissives.get(id);
            if (light == null) continue;
            float w = targetPDF(light, worldPos, surfaceNormal);
            r.update(id, w, rng.nextFloat());
        }

        if (r.selectedLightId < 0) return null;

        EmissiveCache.EmissiveEntry selected = emissives.get(r.selectedLightId);
        if (selected != null) {
            r.finalise(targetPDF(selected, worldPos, surfaceNormal));
        }
        return selected;
    }

    // ── Target PDF ────────────────────────────────────────────────────────────

    /**
     * Estimate sampling importance (target PDF) for a light given a surface point.
     * Proportional to: intensity × (1 / distance²) × max(0, N·L)
     *
     * TODO: add visibility term (shadow ray) for better accuracy.
     */
    private float targetPDF(EmissiveCache.EmissiveEntry light,
                             Vector3f surfacePos,
                             Vector3f surfaceNormal) {
        Vector3f toLight = new Vector3f(light.worldPos()).sub(surfacePos);
        float dist2 = toLight.lengthSquared();
        if (dist2 < 1e-4f) return 0f;

        toLight.normalize();
        float nDotL = Math.max(0f, surfaceNormal.dot(toLight));
        float lum   = 0.2126f * light.color().x
                    + 0.7152f * light.color().y
                    + 0.0722f * light.color().z;

        return light.intensity() * lum * nDotL / dist2;
    }

    // ── Temporal reuse ────────────────────────────────────────────────────────

    /**
     * Resize the previous-frame reservoir buffer when resolution changes.
     * Call once at startup or on window resize.
     */
    public void resizeTemporalBuffer(int width, int height) {
        prevReservoirs = new Reservoir[height][width];
        for (Reservoir[] row : prevReservoirs) {
            for (int i = 0; i < row.length; i++) row[i] = new Reservoir();
        }
    }

    /**
     * End-of-frame: copy current reservoirs into the previous-frame buffer
     * for next frame's temporal reuse pass.
     * (In GPU implementation, this is a simple buffer swap.)
     */
    public void swapTemporalBuffer(Reservoir[][] current) {
        if (prevReservoirs == null
                || prevReservoirs.length    != current.length
                || prevReservoirs[0].length != current[0].length) {
            resizeTemporalBuffer(current[0].length, current.length);
        }
        // shallow swap — both references point to valid arrays
        Reservoir[][] tmp = prevReservoirs;
        prevReservoirs = current;
        // 'tmp' can be reused next frame
    }
}
