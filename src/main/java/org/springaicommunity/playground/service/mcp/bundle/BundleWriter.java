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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BundleWriter {

    private final Path bundleDir;
    private final Path homeDir;
    private final Map<String, String> fileHashes = new LinkedHashMap<>();
    private final Map<String, String> toolHashes = new LinkedHashMap<>();
    private final List<String> upstreamServers = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();
    private List<String> tools = List.of();
    private BundleManifest.ComposedTools composedTools;
    private final List<BundleManifest.RagPipelineRef> ragPipelines = new ArrayList<>();

    BundleWriter(Path bundleDir) {
        this.bundleDir = bundleDir;
        this.homeDir = bundleDir.resolve(BundleManifest.HOME_DIR);
    }

    public Path write(String homeRelativePath, byte[] content) throws IOException {
        Path file = this.homeDir.resolve(homeRelativePath).normalize();
        if (!file.startsWith(this.homeDir)) {
            throw new IllegalArgumentException("Bundle path escapes home/: " + homeRelativePath);
        }
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        this.fileHashes.put(BundleExportService.relativize(this.bundleDir, file), BundleExportService.sha256(file));
        this.files.add(file);
        return file;
    }

    public void toolHash(String toolName, String hash) {
        this.toolHashes.put(toolName, hash);
    }

    public void upstreamServer(String stem) {
        this.upstreamServers.add(stem);
    }

    public void tools(List<String> toolIds) {
        this.tools = List.copyOf(toolIds);
    }

    public void composedTools(BundleManifest.ComposedTools composedTools) {
        this.composedTools = composedTools;
    }

    public void ragPipeline(BundleManifest.RagPipelineRef ref) {
        this.ragPipelines.add(ref);
    }

    Path homeDir() {
        return this.homeDir;
    }

    Map<String, String> fileHashes() {
        return this.fileHashes;
    }

    Map<String, String> toolHashes() {
        return this.toolHashes;
    }

    List<String> upstreamServers() {
        return List.copyOf(this.upstreamServers);
    }

    List<Path> files() {
        return List.copyOf(this.files);
    }

    BundleManifest.Contents contents() {
        return new BundleManifest.Contents(this.tools, this.composedTools, this.ragPipelines, null);
    }

}
