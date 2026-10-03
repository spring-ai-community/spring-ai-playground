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
package org.springaicommunity.playground.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

public class ToolIndexVectorObservationConvention extends DefaultVectorStoreObservationConvention {

    public static final String STORE_ROLE_KEY = "saip.vector.store.role";
    public static final String TOOL_INDEX_ROLE = "tool-index";

    @Override
    public KeyValues getLowCardinalityKeyValues(VectorStoreObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(STORE_ROLE_KEY, TOOL_INDEX_ROLE));
    }
}
