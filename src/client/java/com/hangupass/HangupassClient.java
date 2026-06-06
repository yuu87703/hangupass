package com.hangupass;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HangupassClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(Hangupass.MOD_ID + "-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Client init");
    }
}
