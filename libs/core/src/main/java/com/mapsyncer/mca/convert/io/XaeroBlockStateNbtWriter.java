package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.ChunkSectionParser.BlockState;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class XaeroBlockStateNbtWriter {

    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());
    public static final BlockState WATER = new BlockState("minecraft:water", Map.of());

    private static final ConcurrentHashMap<BlockState, PaletteKey> PALETTE_KEY_CACHE = new ConcurrentHashMap<>();

    private XaeroBlockStateNbtWriter() {}

    public record PaletteKey(String name, List<Map.Entry<String, String>> properties) {

        public static PaletteKey from(BlockState state) {
            if (state == null) {
                return from(AIR);
            }
            return PALETTE_KEY_CACHE.computeIfAbsent(state, s -> {
                TreeMap<String, String> sorted = new TreeMap<>(s.properties());
                return new PaletteKey(s.name(), List.copyOf(sorted.entrySet()));
            });
        }

        public BlockState toBlockState() {
            if (properties.isEmpty()) {
                return new BlockState(name, Map.of());
            }
            var map = new java.util.LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : properties) {
                map.put(e.getKey(), e.getValue());
            }
            return new BlockState(name, Collections.unmodifiableMap(map));
        }
    }

    public static void writeBlockState(BlockState state, DataOutputStream dos) throws IOException {
        BlockState effective = state != null ? state : AIR;
        dos.writeByte(10);
        dos.writeShort(0);

        dos.writeByte(8);
        dos.writeUTF("Name");
        dos.writeUTF(effective.name());

        if (!effective.properties().isEmpty()) {
            dos.writeByte(10);
            dos.writeUTF("Properties");
            TreeMap<String, String> sorted = new TreeMap<>(effective.properties());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                dos.writeByte(8);
                dos.writeUTF(entry.getKey());
                dos.writeUTF(entry.getValue());
            }
            dos.writeByte(0);
        }

        dos.writeByte(0);
    }
}
