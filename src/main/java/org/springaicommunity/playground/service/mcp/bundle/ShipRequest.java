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

import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;

public record ShipRequest(String serverName, String description, String version, List<String> toolIds,
        Transport transport, Integer httpPort, BundleManifest.Models models,
        List<McpComposition.Member> composedTools, RiskLevel composedToolsMaxRisk, List<Knowledge> knowledge) {

    public enum Transport { HTTP, STDIO, BOTH }

    public record Knowledge(String sourceId, String toolName, String description, Integer topK) {}

    public ShipRequest {
        toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
        if (transport == null) transport = Transport.BOTH;
        if (models == null) models = new BundleManifest.Models(null, null);
        composedTools = composedTools == null ? List.of() : List.copyOf(composedTools);
        if (composedToolsMaxRisk == null) composedToolsMaxRisk = RiskLevel.L5;
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public ShipRequest(String serverName, String description, String version, List<String> toolIds,
            Transport transport, Integer httpPort, BundleManifest.Models models,
            List<McpComposition.Member> composedTools, RiskLevel composedToolsMaxRisk) {
        this(serverName, description, version, toolIds, transport, httpPort, models, composedTools,
                composedToolsMaxRisk, null);
    }

    public ShipRequest withModels(BundleManifest.Models newModels) {
        return new ShipRequest(serverName, description, version, toolIds, transport, httpPort, newModels,
                composedTools, composedToolsMaxRisk, knowledge);
    }

    public ShipRequest(String serverName, String description, String version, List<String> toolIds,
            Transport transport, Integer httpPort, BundleManifest.Models models) {
        this(serverName, description, version, toolIds, transport, httpPort, models, null, null);
    }

    public int toolCount() {
        return toolIds.size() + composedTools.size() + knowledge.size();
    }

    public int effectiveHttpPort() {
        return httpPort == null ? BundleExportService.DEFAULT_HTTP_PORT : httpPort;
    }
}
