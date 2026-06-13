package com.rtbridge.event;

@FunctionalInterface
public interface DirtyEventListener {
    void onDirtyEvent(DirtyEvent event);
}
