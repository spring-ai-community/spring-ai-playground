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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShipDraft {

    private final List<String> blockers = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, BundleManifest.EnvVar> env = new LinkedHashMap<>();
    private final List<ShippedTool> tools = new ArrayList<>();

    public void block(String blocker) {
        this.blockers.add(blocker);
    }

    public void warn(String warning) {
        this.warnings.add(warning);
    }

    public void requireEnv(BundleManifest.EnvVar envVar) {
        this.env.putIfAbsent(envVar.name(), envVar);
    }

    public void ship(ShippedTool tool) {
        this.tools.add(tool);
    }

    public List<String> blockers() {
        return List.copyOf(this.blockers);
    }

    public List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    public List<BundleManifest.EnvVar> env() {
        return List.copyOf(this.env.values());
    }

    public List<ShippedTool> tools() {
        return List.copyOf(this.tools);
    }

}
