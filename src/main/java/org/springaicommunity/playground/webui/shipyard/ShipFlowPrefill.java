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
package org.springaicommunity.playground.webui.shipyard;

import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;

import java.util.List;
import java.util.Map;

public record ShipFlowPrefill(ShipRequest request, Map<String, String> tryQueries) {

    public ShipFlowPrefill {
        tryQueries = tryQueries == null ? Map.of() : Map.copyOf(tryQueries);
    }

    public static ShipFlowPrefill of(ShipRequest request) {
        return new ShipFlowPrefill(request, Map.of());
    }

    public static ShipFlowPrefill knowledge(String sourceId, String tryQuery) {
        ShipRequest request = new ShipRequest(null, null, null, List.of(), null, null, null, List.of(), null,
                List.of(new ShipRequest.Knowledge(sourceId, null, null, null)));
        return new ShipFlowPrefill(request, tryQuery == null || tryQuery.isBlank() ? Map.of()
                : Map.of(sourceId, tryQuery));
    }

}
