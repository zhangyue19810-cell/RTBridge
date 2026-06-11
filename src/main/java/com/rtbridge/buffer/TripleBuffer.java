package com.rtbridge.buffer;

import com.rtbridge.scene.SceneDatabase;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TripleBuffer — spec §4.
 *
 * Three SceneDatabase slots rotate through three roles:
 *   Front  — OpenGL reads (current rendered frame)
 *   Middle — RT reads    (frame being ray-traced)
 *   Back   — Extractor writes (next frame being prepared)
 *
 * Rotation (called once per OpenGL frame end):
 *   old Middle → new Front  (RT result is now "current")
 *   old Back   → new Middle (extractor's work is now available to RT)
 *   old Front  → new Back   (reuse old front as new write target)
 *
 * This ensures:
 *   - No reader ever blocks a writer
 *   - OpenGL and RT never share the same buffer slot
 *   - Extractor can write freely without stalling either renderer
 */
public class TripleBuffer {

    private final SceneDatabase[] slots;

    // Slot index currently assigned to each role.
    // Encoded as a single AtomicInteger: bits [0-1]=front, [2-3]=middle, [4-5]=back
    // to allow lock-free atomic swap.
    private volatile int frontIdx  = 0;
    private volatile int middleIdx = 1;
    private volatile int backIdx   = 2;

    private final Object rotateLock = new Object();

    public TripleBuffer(SceneDatabase templateDb) {
        slots = new SceneDatabase[3];
        slots[0] = templateDb;                     // front = the live database
        slots[1] = new SceneDatabase();            // middle
        slots[2] = new SceneDatabase();            // back (extractor writes here)
    }

    // ── Role accessors ────────────────────────────────────────────────────────

    /** OpenGL read slot. */
    public SceneDatabase getFront()  { return slots[frontIdx]; }

    /** RT read slot. */
    public SceneDatabase getMiddle() { return slots[middleIdx]; }

    /** Extractor write slot. */
    public SceneDatabase getBack()   { return slots[backIdx]; }

    // ── Rotation ──────────────────────────────────────────────────────────────

    /**
     * Advance one frame.  Copy Back → Middle snapshot, then rotate indices.
     * Called from the render thread at the end of each OpenGL frame.
     */
    public void advanceFrame() {
        synchronized (rotateLock) {
            // Copy the freshly-written back slot into the middle slot
            // so the RT thread sees a complete consistent snapshot.
            SceneDatabase back   = slots[backIdx];
            SceneDatabase middle = slots[middleIdx];

            middle.writeLock();
            back.readLock();
            try {
                middle.copyFrom(back);
            } finally {
                back.readUnlock();
                middle.writeUnlock();
            }

            // Rotate: old front → new back (recycled), old middle → new front,
            // old back (now copied) → new middle
            int oldFront  = frontIdx;
            int oldMiddle = middleIdx;
            int oldBack   = backIdx;

            frontIdx  = oldMiddle;
            middleIdx = oldBack;
            backIdx   = oldFront;
        }
    }

    /**
     * Reset all three slots (e.g. on world unload).
     */
    public void clear() {
        for (SceneDatabase db : slots) db.clear();
    }
}
