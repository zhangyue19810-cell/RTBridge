package com.rtbridge.event;

/**
 * All world-state change events that can dirty the Scene Database.
 * New event types should be added here; the DirtyEventSystem dispatches them.
 *
 * Categories:
 *   CHUNK_*       — terrain / static geometry
 *   BLOCK_*       — individual block changes
 *   ENTITY_*      — mobs, items, players
 *   SHIP_*        — Valkyrien Skies ships / Aeronautics airships
 *   LIGHT_*       — emissive sources (Sable, Create lamps, etc.)
 */
public enum DirtyEventType {

    // ── Chunk ────────────────────────────────────────────────────────────────
    /** A chunk was loaded from disk or generated. Full BLAS needed. */
    CHUNK_LOAD,
    /** A chunk was unloaded. Remove its BLAS entry. */
    CHUNK_UNLOAD,
    /** One or more sections inside a chunk were re-meshed by Sodium/vanilla. */
    CHUNK_REMESH,

    // ── Blocks ───────────────────────────────────────────────────────────────
    BLOCK_PLACE,
    BLOCK_BREAK,
    /** BlockEntity changed state (e.g. chest open, furnace on/off). */
    BLOCK_ENTITY_UPDATE,

    // ── Entities ─────────────────────────────────────────────────────────────
    ENTITY_SPAWN,
    /** Entity moved or rotated — update TLAS transform only, skip BLAS. */
    ENTITY_MOVE,
    ENTITY_REMOVE,

    // ── Valkyrien Skies ships ────────────────────────────────────────────────
    /** New ship assembled. Trigger async BLAS build + TLAS instance add. */
    SHIP_CREATE,
    /** Ship translated — update TLAS Instance Transform only. */
    SHIP_MOVE,
    /** Ship rotated — update TLAS Instance Transform only. */
    SHIP_ROTATE,
    /** Ship disassembled or destroyed. Remove BLAS + TLAS instance. */
    SHIP_DESTROY,
    /** Blocks on a ship were added/removed. Queue BLAS rebuild. */
    SHIP_MODIFIED,

    // ── Lights ───────────────────────────────────────────────────────────────
    LIGHT_ADD,
    LIGHT_REMOVE,
    LIGHT_UPDATE,
}
