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
package org.springaicommunity.playground.service.chat;

import java.util.List;

// Static per-conversation settings baked into ChatHistory at "Apply & New Chat" time (the drawer). Reasoning is NOT
// here - it is a dynamic per-request value (see ReasoningEffort). null fields leave the model/provider default.
public record ChatExtraOptions(Integer seed, List<String> stop, String providerOptionsJson) {

    public static ChatExtraOptions defaults() {
        return new ChatExtraOptions(null, null, null);
    }
}
