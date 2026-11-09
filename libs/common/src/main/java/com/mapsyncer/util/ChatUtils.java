package com.mapsyncer.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ChatUtils {

    public static final int PREFIX_COLOR = 0xFFE55E;

    public static final int SUCCESS_COLOR = 0x55FF55;

    public static final int ERROR_COLOR = 0xFF5555;

    public static final int NORMAL_COLOR = 0xFFFFFF;

    public static final int DESC_COLOR = 0xAAAAAA;

    public static final int HEADER_COLOR = 0xFFFF55;

    private ChatUtils() {

    }

    public static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(PREFIX_COLOR));
    }

    public static MutableComponent success(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    public static MutableComponent success(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    public static MutableComponent error(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    public static MutableComponent error(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    public static MutableComponent message(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    public static MutableComponent message(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    public static MutableComponent desc(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(DESC_COLOR));
    }

    public static MutableComponent desc(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(DESC_COLOR));
    }

    public static MutableComponent header(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(HEADER_COLOR));
    }
}
