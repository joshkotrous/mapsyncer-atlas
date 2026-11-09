package com.mapsyncer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record LayerPlan(
    boolean includeSurface,
    boolean includeAllCaves,
    List<Integer> caveStarts
) {
    public static final int DEFAULT_CAVE_START = 63;

    public LayerPlan {
        caveStarts = caveStarts == null || caveStarts.isEmpty()
            ? List.of()
            : List.copyOf(caveStarts);
    }

    public static LayerPlan empty() {
        return new LayerPlan(false, false, List.of());
    }

    public static LayerPlan surfaceOnly() {
        return new LayerPlan(true, false, List.of());
    }

    public static LayerPlan allCaves() {
        return new LayerPlan(false, true, List.of());
    }

    public static LayerPlan caves(int... starts) {
        if (starts == null || starts.length == 0) {
            return empty();
        }
        return new LayerPlan(false, false, java.util.Arrays.stream(starts).boxed().toList());
    }

    public static LayerPlan mixed(int... caveStarts) {
        if (caveStarts == null || caveStarts.length == 0) {
            return surfaceOnly();
        }
        return new LayerPlan(true, false, java.util.Arrays.stream(caveStarts).boxed().toList());
    }

    public boolean isEmpty() {
        return !includeSurface && !includeAllCaves && caveStarts.isEmpty();
    }

    public int primaryCaveStart() {
        return caveStarts.isEmpty() ? DEFAULT_CAVE_START : caveStarts.get(0);
    }

    public String toConfigString() {
        if (isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (includeSurface) {
            parts.add("SURFACE");
        }
        if (includeAllCaves) {
            parts.add("ALL");
        }
        caveStarts.forEach(y -> parts.add(String.valueOf(y)));
        return String.join(",", parts);
    }

    public static LayerPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return empty();
        }

        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("SURFACE")) {
            return surfaceOnly();
        }
        if (trimmed.equalsIgnoreCase("ALL")) {
            return allCaves();
        }

        if (!trimmed.contains(",")) {
            try {
                return caves(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                return empty();
            }
        }

        boolean surface = false;
        boolean allCaves = false;
        List<Integer> starts = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SURFACE")) {
                surface = true;
            } else if (token.equalsIgnoreCase("ALL")) {
                allCaves = true;
            } else {
                try {
                    starts.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {

                }
            }
        }

        if (!surface && !allCaves && starts.isEmpty()) {
            return empty();
        }
        return new LayerPlan(surface, allCaves, Collections.unmodifiableList(starts));
    }

    public static LayerPlan fromLegacy(ScanMode scanMode, String caveField) {
        LayerPlan parsed = parse(caveField);
        if (scanMode == ScanMode.SURFACE) {
            if (!parsed.includeAllCaves() && parsed.caveStarts().isEmpty()) {
                return surfaceOnly();
            }
            return new LayerPlan(true, parsed.includeAllCaves(), parsed.caveStarts());
        }
        return parsed;
    }
}
