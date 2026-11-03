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

import java.util.List;

public record ServerSelection(String server, boolean all, List<ToolPick> tools, HitlPolicy hitl) {

    public ServerSelection {
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (hitl == null) hitl = HitlPolicy.ALL_TOOLS;
    }

    public static ServerSelection allTools(String server, HitlPolicy hitl) {
        return new ServerSelection(server, true, List.of(), hitl);
    }

    public static ServerSelection of(String server, List<ToolPick> tools, HitlPolicy hitl) {
        return new ServerSelection(server, false, tools, hitl);
    }

}
