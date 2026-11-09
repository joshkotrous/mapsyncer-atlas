package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class XaeroPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroPathResolver.class);

    private XaeroPathResolver() {}

    public static Path getIntegratedWorldMapDir(Path gameDir, Path saveDir) {
        String folder = saveDir.toAbsolutePath().normalize().getFileName().toString();
        try {
            String id = (String) Class.forName("xaero.map.world.MapWorld")
                    .getMethod("convertWorldFolderToRootId", int.class, String.class).invoke(null, 5, folder);
            Path root = (Path) Class.forName("xaero.map.file.MapSaveLoad")
                    .getMethod("getRootFolder", String.class).invoke(null, id);
            if (root != null) return root.toAbsolutePath().normalize();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.debug("Xaero root API not ready, using world-map directory", e);
        }
        return getWorldMapDir(gameDir).resolve(folder).toAbsolutePath().normalize();
    }

    public static Path getWorldMapDir(Path gameDir) {
        Path modern = gameDir.resolve("xaero").resolve("world-map");
        if (Files.isDirectory(modern)) {
            LOGGER.debug("Detected Xaero WorldMap path: {}", modern);
            return modern;
        }
        Path legacy = gameDir.resolve("XaeroWorldMap");
        if (Files.isDirectory(legacy)) {
            LOGGER.debug("Detected Xaero WorldMap legacy path: {}", legacy);
            return legacy;
        }
        LOGGER.debug("No Xaero WorldMap directory found, defaulting to: {}", modern);
        return modern;
    }
}
