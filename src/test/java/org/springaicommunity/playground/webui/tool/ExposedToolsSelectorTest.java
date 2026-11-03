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
package org.springaicommunity.playground.webui.tool;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolSpec;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExposedToolsSelectorTest {

    private static ToolSpec spec(String id, String name) {
        return new ToolSpec(id, name, "d", List.of(), List.of(), "code", ToolSpec.CodeType.Javascript, null);
    }

    @Test
    void resolveBuiltinSelectionSelectsExposed() {
        ToolSpec a = spec("builtin-active", "a");
        ToolSpec b = spec("builtin-draft", "b");
        assertThat(ExposedToolsSelector.resolveBuiltinSelection(List.of(a, b), Set.of("builtin-active")))
                .extracting(ToolSpec::toolId).containsExactly("builtin-active");
    }

    @Test
    void emptyInputsProduceEmptyResults() {
        assertThat(ExposedToolsSelector.resolveBuiltinSelection(List.of(), Set.of())).isEmpty();
    }
}
