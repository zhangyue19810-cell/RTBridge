package com.rtbridge.fabric;

import com.rtbridge.RTBridgeMod;
import net.fabricmc.api.ClientModInitializer;

public class RTBridgeFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RTBridgeMod.init();
    }
}
