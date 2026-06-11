package com.rtbridge.event;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable event record.  Carry only the minimal data needed to route
 * the event to the right cache updater — no full world snapshots.
 */
public final class DirtyEvent {

    public final DirtyEventType type;

    // optional payloads — null when not relevant for this event type
    @Nullable public final ChunkPos  chunkPos;
    @Nullable public final BlockPos  blockPos;
    @Nullable public final Long      entityId;
    @Nullable public final Long      shipId;

    private DirtyEvent(Builder b) {
        this.type      = b.type;
        this.chunkPos  = b.chunkPos;
        this.blockPos  = b.blockPos;
        this.entityId  = b.entityId;
        this.shipId    = b.shipId;
    }

    public static Builder of(DirtyEventType type) {
        return new Builder(type);
    }

    // ── Builder ───────────────────────────────────────────────────────────────
    public static final class Builder {
        private final DirtyEventType type;
        private ChunkPos chunkPos;
        private BlockPos blockPos;
        private Long     entityId;
        private Long     shipId;

        private Builder(DirtyEventType type) { this.type = type; }

        public Builder chunk(ChunkPos pos)  { this.chunkPos = pos;  return this; }
        public Builder block(BlockPos pos)  { this.blockPos = pos;  return this; }
        public Builder entity(long id)      { this.entityId = id;   return this; }
        public Builder ship(long id)        { this.shipId   = id;   return this; }

        public DirtyEvent build() { return new DirtyEvent(this); }
    }

    @Override
    public String toString() {
        return "DirtyEvent{" + type
            + (chunkPos != null ? ", chunk=" + chunkPos : "")
            + (blockPos != null ? ", block=" + blockPos : "")
            + (entityId != null ? ", entity=" + entityId : "")
            + (shipId   != null ? ", ship="   + shipId   : "")
            + '}';
    }
}
