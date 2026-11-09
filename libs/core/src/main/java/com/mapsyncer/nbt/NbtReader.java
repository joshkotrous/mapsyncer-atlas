package com.mapsyncer.nbt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NbtReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NbtReader.class);

    private static final int MAX_ARRAY_SIZE = 125_000;

    private static final int MAX_LIST_SIZE = 100_000;

    private static final int MAX_COMPOUND_DEPTH = 30;

    private final DataInputStream in;

    private int currentDepth = 0;

    public NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    public Tag.Compound readDocument() throws IOException {
        byte type = in.readByte();
        if (type != Tag.TAG_COMPOUND) {
            throw new IOException("NBT document must begin with Compound; actual type: " + type);
        }
        String name = in.readUTF();
        return readCompoundContent(name);
    }

    public Tag readTag() throws IOException {
        byte type = in.readByte();
        if (type == Tag.TAG_END) {
            return new Tag.End();
        }
        String name = in.readUTF();
        return readPayload(type, name);
    }

    private Tag readPayload(byte type, String name) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return new Tag.End();
            case Tag.TAG_BYTE:
                return new Tag.Byte(name, in.readByte());
            case Tag.TAG_SHORT:
                return new Tag.Short(name, in.readShort());
            case Tag.TAG_INT:
                return new Tag.Int(name, in.readInt());
            case Tag.TAG_LONG:
                return new Tag.Long(name, in.readLong());
            case Tag.TAG_FLOAT:
                return new Tag.Float(name, in.readFloat());
            case Tag.TAG_DOUBLE:
                return new Tag.Double(name, in.readDouble());
            case Tag.TAG_BYTE_ARRAY:
                return readByteArray(name);
            case Tag.TAG_STRING:
                return new Tag.StringTag(name, in.readUTF());
            case Tag.TAG_LIST:
                return readListContent(name);
            case Tag.TAG_COMPOUND:
                return readCompoundContent(name);
            case Tag.TAG_INT_ARRAY:
                return readIntArray(name);
            case Tag.TAG_LONG_ARRAY:
                return readLongArray(name);
            default:
                throw new IOException("Unknown NBT type: " + type);
        }
    }

    private Tag.ByteArray readByteArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("ByteArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: ByteArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("ByteArray length exceeds the limit: " + length + " (maximum " + MAX_ARRAY_SIZE + ")");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Tag.ByteArray(name, data);
    }

    private Tag.IntArray readIntArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("IntArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: IntArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("IntArray length exceeds the limit: " + length + " (maximum " + MAX_ARRAY_SIZE + ")");
        }
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readInt();
        }
        return new Tag.IntArray(name, data);
    }

    private Tag.LongArray readLongArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("LongArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: LongArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("LongArray length exceeds the limit: " + length + " (maximum " + MAX_ARRAY_SIZE + ")");
        }
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readLong();
        }
        return new Tag.LongArray(name, data);
    }

    private Tag.ListTag readListContent(String name) throws IOException {
        byte elementType = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("List length cannot be negative: " + length);
        }
        if (length > MAX_LIST_SIZE) {
            LOGGER.warn("NBT size limit exceeded: List '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_LIST_SIZE);
            throw new IOException("List length exceeds the limit: " + length + " (maximum " + MAX_LIST_SIZE + ")");
        }
        List<Tag> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {

            items.add(readPayload(elementType, ""));
        }
        return new Tag.ListTag(name, elementType, items);
    }

    private Tag.Compound readCompoundContent(String name) throws IOException {
        currentDepth++;
        if (currentDepth > MAX_COMPOUND_DEPTH) {
            LOGGER.warn("NBT depth limit exceeded: Compound '{}' depth={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, currentDepth, MAX_COMPOUND_DEPTH);
            throw new IOException("Compound nesting depth exceeds the limit: " + currentDepth + " (maximum " + MAX_COMPOUND_DEPTH + ")");
        }

        Map<String, Tag> children = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.TAG_END) {
                currentDepth--;
                break;
            }
            String childName = in.readUTF();
            children.put(childName, readPayload(type, childName));
        }
        return new Tag.Compound(name, children);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
