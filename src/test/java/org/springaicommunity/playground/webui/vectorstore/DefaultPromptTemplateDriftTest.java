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
package org.springaicommunity.playground.webui.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-enforced check that the prompt templates the wizard pre-fills are still the ones Spring AI would
 * actually apply. The framework keeps its defaults in private fields with no accessor, so the wizard has
 * to hold copies; saving a value equal to the copy stores null, which means the pipeline runs Spring AI's
 * default rather than ours.
 *
 * Reflection reads the originals so a Spring AI upgrade that reworded any template FAILS here, instead of
 * silently leaving the wizard showing one prompt while retrieval runs another.
 */
class DefaultPromptTemplateDriftTest {

    @Test
    void rewriteTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_REWRITE_TEMPLATE)
                .isEqualTo(springAiTemplate(RewriteQueryTransformer.class, "DEFAULT_PROMPT_TEMPLATE"));
    }

    @Test
    void compressionTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_COMPRESSION_TEMPLATE)
                .isEqualTo(springAiTemplate(CompressionQueryTransformer.class, "DEFAULT_PROMPT_TEMPLATE"));
    }

    @Test
    void translationTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_TRANSLATION_TEMPLATE)
                .isEqualTo(springAiTemplate(TranslationQueryTransformer.class, "DEFAULT_PROMPT_TEMPLATE"));
    }

    @Test
    void multiQueryTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_MULTI_QUERY_TEMPLATE)
                .isEqualTo(springAiTemplate(MultiQueryExpander.class, "DEFAULT_PROMPT_TEMPLATE"));
    }

    @Test
    void augmenterTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_AUGMENTER_TEMPLATE)
                .isEqualTo(springAiTemplate(ContextualQueryAugmenter.class, "DEFAULT_PROMPT_TEMPLATE"));
    }

    @Test
    void emptyContextTemplateMatchesSpringAiDefault() {
        assertThat(NewRagPipelineDialog.DEFAULT_EMPTY_CONTEXT_TEMPLATE)
                .isEqualTo(springAiTemplate(ContextualQueryAugmenter.class, "DEFAULT_EMPTY_CONTEXT_PROMPT_TEMPLATE"));
    }

    private static String springAiTemplate(Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return ((PromptTemplate) field.get(null)).getTemplate();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + fieldName
                    + " is gone, so the wizard's copied default can no longer be verified", e);
        }
    }
}
