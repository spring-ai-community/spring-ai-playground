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
package org.springaicommunity.playground.service.mcp.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpCategoryServiceTest {

    private final McpCategoryService service = new McpCategoryService(new ObjectMapper());

    @Test
    void built_in_categories_loaded_in_order() {
        List<CategoryDef> defs = service.getBuiltInCategories();
        assertThat(defs).extracting(CategoryDef::id)
                .startsWith("PRODUCTIVITY", "STORAGE", "COMMUNICATION", "PROJECT_MGMT", "DEV", "SEARCH",
                        "CLOUD", "DATABASE", "FINANCE", "CRM", "DESIGN", "UTIL")
                .endsWith("CUSTOM");
        assertThat(defs).allMatch(CategoryDef::builtIn);
    }

    @Test
    void user_defined_categories_appended_alphabetically_after_built_in() {
        List<CategoryDef> all = service.getAllCategories(Set.of("personal", "PRODUCTIVITY", "experimental"));
        // Built-in defs stay in order
        assertThat(all).extracting(CategoryDef::id)
                .startsWith("PRODUCTIVITY")
                .contains("CUSTOM")
                .endsWith("personal");  // alphabetical: experimental then personal
        // Built-in PRODUCTIVITY must remain marked builtIn even though it was observed in user data
        assertThat(all.stream().filter(d -> d.id().equals("PRODUCTIVITY")).findFirst())
                .hasValueSatisfying(d -> assertThat(d.builtIn()).isTrue());
        // User-defined ids get synthetic defs flagged builtIn=false
        assertThat(all.stream().filter(d -> d.id().equals("personal")).findFirst())
                .hasValueSatisfying(d -> {
                    assertThat(d.builtIn()).isFalse();
                    assertThat(d.order()).isGreaterThan(1000);
                });
    }

    @Test
    void resolve_def_returns_built_in_when_known_synthetic_otherwise() {
        assertThat(service.resolveDef("DEV").builtIn()).isTrue();
        CategoryDef synth = service.resolveDef("personal");
        assertThat(synth.builtIn()).isFalse();
        assertThat(synth.id()).isEqualTo("personal");
    }

    @Test
    void resolve_def_with_blank_falls_back_to_custom() {
        assertThat(service.resolveDef(null).id()).isEqualTo("CUSTOM");
        assertThat(service.resolveDef("").id()).isEqualTo("CUSTOM");
    }
}
