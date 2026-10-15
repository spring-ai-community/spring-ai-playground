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

import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

public record ShippedTool(String name, Kind kind, String description, String riskLabel, RiskLevel riskCap,
        boolean hitl) {

    public enum Kind {
        TOOL("Tool"), BUILT_IN("Built-in"), PROXIED("Proxied"), KNOWLEDGE("Knowledge");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

}
