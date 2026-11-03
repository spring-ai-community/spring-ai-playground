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

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.Locale;
import java.util.stream.IntStream;

final class AxisEmbeddingModel implements EmbeddingModel {

    static float[] vectorFor(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("alpha")) return new float[]{1f, 0f, 0f};
        if (lower.contains("beta")) return new float[]{0f, 1f, 0f};
        return new float[]{0f, 0f, 1f};
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                .mapToObj(index -> new Embedding(vectorFor(request.getInstructions().get(index)), index))
                .toList());
    }

    @Override
    public float[] embed(Document document) {
        return vectorFor(document.getText());
    }

}
