package com.mapsyncer.util;

import java.util.Properties;

public final class PropertiesHelper {

    private PropertiesHelper() {}

    public static String get(Properties props, String primaryKey, String alternateKey, String defaultValue) {
        String value = props.getProperty(primaryKey);
        if (value == null && alternateKey != null) {
            value = props.getProperty(alternateKey);
        }
        return value != null ? value : defaultValue;
    }
}
