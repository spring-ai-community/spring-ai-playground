/*
 * Copyright © 2025 Jemin Huh (hjm1980@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.playground.service.mcp.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class BundleHome {

    public static final String SOURCE_PROPERTY = "spring.ai.playground.bundle-source";
    public static final String IMAGE_SOURCE_DIR = "/opt/saip-bundle";

    private static final Logger logger = LoggerFactory.getLogger(BundleHome.class);

    private BundleHome() {}

    public static Path prepareWorkingHome(Path source) {
        if (!Files.isRegularFile(source.resolve(BundleManifest.FILE_NAME))) {
            throw new IllegalStateException(SOURCE_PROPERTY + " points at " + source + ", which holds no "
                    + BundleManifest.FILE_NAME);
        }
        try {
            Path workingHome = Files.createTempDirectory("saip-bundle-home-");
            copyTree(source, workingHome);
            logger.info("Serving bundle from {}; this run works in a fresh copy at {}", source, workingHome);
            return workingHome;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare a working home from bundle " + source, e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(source)) {
            paths = walk.toList();
        }
        for (Path path : paths) {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.copy(path, destination);
            }
        }
    }

}
