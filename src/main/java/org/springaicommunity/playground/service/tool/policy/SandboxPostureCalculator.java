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
package org.springaicommunity.playground.service.tool.policy;

import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class SandboxPostureCalculator {

    public record Inputs(String networkMode, Set<String> allowedHosts,
                         Set<String> baselineDeny, Set<String> userDeny,
                         Set<String> baselineAllow, Set<String> userAllow) {}

    public RiskLevel compute(Inputs inputs) {
        RiskLevel level = RiskLevel.L0;

        if ("allowlist".equals(inputs.networkMode())) {
            Set<String> hosts = inputs.allowedHosts();
            if (hosts != null && hosts.contains("*")) {
                level = max(level, RiskLevel.L4);
            } else if (hosts != null && !hosts.isEmpty()) {
                level = max(level, RiskLevel.L3);
            }
        }
        if ("open".equals(inputs.networkMode())) level = max(level, RiskLevel.L4);

        Set<String> removedDeny = new HashSet<>(inputs.baselineDeny() == null ? Set.of() : inputs.baselineDeny());
        if (inputs.userDeny() != null) removedDeny.removeAll(inputs.userDeny());
        if (!removedDeny.isEmpty()) {
            if (removedDeny.stream().anyMatch(SandboxPostureCalculator::isCriticalClass)) {
                level = max(level, RiskLevel.L5);
            } else if (removedDeny.size() >= 3) {
                level = max(level, RiskLevel.L4);
            } else {
                level = max(level, RiskLevel.L3);
            }
        }

        Set<String> addedAllow = new HashSet<>(inputs.userAllow() == null ? Set.of() : inputs.userAllow());
        addedAllow.removeAll(inputs.baselineAllow() == null ? Set.of() : inputs.baselineAllow());
        if (!addedAllow.isEmpty()) {
            if (addedAllow.stream().anyMatch(SandboxPostureCalculator::isCriticalClass)) {
                level = max(level, RiskLevel.L5);
            } else if (addedAllow.stream().anyMatch(SandboxPostureCalculator::isReflectionClass)) {
                level = max(level, RiskLevel.L4);
            } else {
                level = max(level, RiskLevel.L3);
            }
        }
        return level;
    }

    static boolean isCriticalClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.lang.System")
                || name.startsWith("java.lang.Runtime")
                || name.startsWith("java.lang.Process")
                || name.startsWith("java.lang.ProcessBuilder");
    }

    static boolean isReflectionClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.lang.reflect")
                || name.startsWith("java.lang.invoke")
                || name.equals("java.lang.Class");
    }

    private static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
