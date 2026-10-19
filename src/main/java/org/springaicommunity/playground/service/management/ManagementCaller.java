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

public record ManagementCaller(String clientName, String clientVersion, boolean interactive,
                               boolean supportsElicitation, boolean selfLoopback, ManagementProgress progress) {

    public static final ManagementCaller ANONYMOUS = new ManagementCaller("unknown", "", false, false, false);

    public ManagementCaller {
        progress = progress == null ? ManagementProgress.NONE : progress;
    }

    public ManagementCaller(String clientName, String clientVersion, boolean interactive,
            boolean supportsElicitation, boolean selfLoopback) {
        this(clientName, clientVersion, interactive, supportsElicitation, selfLoopback, ManagementProgress.NONE);
    }

    public String label() {
        return this.clientVersion == null || this.clientVersion.isBlank()
                ? this.clientName : this.clientName + " " + this.clientVersion;
    }

}
