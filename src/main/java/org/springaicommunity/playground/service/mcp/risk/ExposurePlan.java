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

import java.util.List;

public record ExposurePlan(ExposureChange.Operation operation, boolean managedByConfiguration, RiskLevel maxRisk,
                           List<PlannedMember> members, List<McpComposition.Member> resultingMembers,
                           List<Rejection> rejections, List<String> warnings, int exposedBefore,
                           boolean composedToolsVisible) {

    public enum Change {
        ADDED, UPDATED, REMOVED, UNCHANGED
    }

    public record PlannedMember(String server, String tool, String alias, String description, boolean hitl,
                                RiskLevel inherentRisk, Change change) {
    }

    public record Rejection(String server, String tool, String reason) {
    }

    public ExposurePlan {
        members = members == null ? List.of() : List.copyOf(members);
        resultingMembers = resultingMembers == null ? List.of() : List.copyOf(resultingMembers);
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean accepted() {
        return this.rejections.isEmpty();
    }

    public int exposedAfter() {
        return (int) this.members.stream().filter(member -> member.change() != Change.REMOVED).count();
    }

}
