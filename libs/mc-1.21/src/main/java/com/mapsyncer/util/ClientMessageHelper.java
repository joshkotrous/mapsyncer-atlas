package com.mapsyncer.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ClientMessageHelper {

    private ClientMessageHelper() {}

    public static void sendChatMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, false);
        }
    }

    public static void sendOverlayMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, true);
        }
    }
}
