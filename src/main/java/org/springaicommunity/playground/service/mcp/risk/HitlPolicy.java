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
package org.springaicommunity.playground.service.mcp.risk;

import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.Set;

public record HitlPolicy(Mode mode, RiskLevel atLeast, Set<String> except) {

    public enum Mode {
        ALL, NONE, RISK
    }

    public static final HitlPolicy ALL_TOOLS = new HitlPolicy(Mode.ALL, null, Set.of());

    public HitlPolicy {
        if (mode == null) mode = Mode.ALL;
        if (mode == Mode.RISK && atLeast == null) atLeast = RiskLevel.L3;
        except = except == null ? Set.of() : Set.copyOf(except);
    }

    public boolean requires(String toolName, RiskLevel inherentRisk) {
        boolean byDefault = switch (this.mode) {
            case ALL -> true;
            case NONE -> false;
            case RISK -> inherentRisk != null && inherentRisk.ordinal() >= this.atLeast.ordinal();
        };
        return this.except.contains(toolName) != byDefault;
    }

}
