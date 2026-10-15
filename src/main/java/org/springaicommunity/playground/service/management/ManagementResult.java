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
package org.springaicommunity.playground.service.management;

import java.util.ArrayList;
import java.util.List;

public record ManagementResult(boolean ok, String message, boolean persisted, boolean restartRequired,
                               List<String> warnings, Object data) {

    public ManagementResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ManagementResult ok(String message, Object data) {
        return new ManagementResult(true, message, false, false, List.of(), data);
    }

    public static ManagementResult persisted(String message, Object data) {
        return new ManagementResult(true, message, true, false, List.of(), data);
    }

    public static ManagementResult failure(String message) {
        return new ManagementResult(false, message, false, false, List.of(), null);
    }

    public static ManagementResult failure(String message, Object data) {
        return new ManagementResult(false, message, false, false, List.of(), data);
    }

    public ManagementResult withWarnings(List<String> additional) {
        List<String> merged = new ArrayList<>(this.warnings);
        merged.addAll(additional);
        return new ManagementResult(this.ok, this.message, this.persisted, this.restartRequired, merged, this.data);
    }

    public ManagementResult requiringRestart() {
        return new ManagementResult(this.ok, this.message, this.persisted, true, this.warnings, this.data);
    }

}
