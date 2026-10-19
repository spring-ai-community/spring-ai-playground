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

import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeContribution.Source;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class KnowledgeSuggestService {

    public enum Reason { PIPELINE, ALL_DOCUMENTS_PIPELINE, UNCOVERED_DOCUMENT }

    public record Suggestion(ShipRequest.Knowledge knowledge, String sourceName, Reason reason, int documents,
            List<String> llmStages) {}

    private final KnowledgeContribution knowledgeContribution;
    private final RagPipelineService ragPipelineService;
    private final ObjectProvider<OfflineEtlPipelineService> documentsProvider;

    public KnowledgeSuggestService(KnowledgeContribution knowledgeContribution,
            RagPipelineService ragPipelineService, ObjectProvider<OfflineEtlPipelineService> documentsProvider) {
        this.knowledgeContribution = knowledgeContribution;
        this.ragPipelineService = ragPipelineService;
        this.documentsProvider = documentsProvider;
    }

    public List<Suggestion> suggest(Collection<String> selectedSourceIds, Collection<String> takenToolNames) {
        Set<String> selected = new LinkedHashSet<>(selectedSourceIds == null ? List.of() : selectedSourceIds);
        Set<String> taken = new LinkedHashSet<>(takenToolNames == null ? List.of() : takenToolNames);
        Set<String> covered = new LinkedHashSet<>();
        selected.forEach(sourceId -> this.knowledgeContribution.resolve(sourceId)
                .ifPresent(source -> source.documents().forEach(info -> covered.add(info.docInfoId()))));

        List<Suggestion> suggestions = new ArrayList<>();
        List<Source> allDocumentPipelines = new ArrayList<>();
        for (RagPipeline pipeline : this.ragPipelineService.list()) {
            Optional<Source> resolved = this.knowledgeContribution.resolve(pipeline.id());
            if (resolved.isEmpty() || !shippable(resolved.get())) continue;
            Source source = resolved.get();
            if (pipeline.docInfoIds().isEmpty()) {
                allDocumentPipelines.add(source);
                continue;
            }
            if (!selected.contains(source.sourceId())) {
                suggestions.add(suggestion(source, Reason.PIPELINE, taken));
            }
            source.documents().forEach(info -> covered.add(info.docInfoId()));
        }

        List<VectorStoreDocumentInfo> uncovered = knowledgeDocuments().stream()
                .filter(info -> !covered.contains(info.docInfoId())).toList();
        if (uncovered.isEmpty()) return suggestions;
        Optional<Source> everything = allDocumentPipelines.stream()
                .filter(source -> !selected.contains(source.sourceId())).findFirst();
        if (everything.isPresent()) {
            suggestions.add(suggestion(everything.get(), Reason.ALL_DOCUMENTS_PIPELINE, taken));
            return suggestions;
        }
        for (VectorStoreDocumentInfo info : uncovered) {
            String sourceId = ChatService.RAG_DOCUMENT_SOURCE_PREFIX + info.docInfoId();
            if (selected.contains(sourceId)) continue;
            this.knowledgeContribution.resolve(sourceId).filter(KnowledgeSuggestService::shippable)
                    .ifPresent(source -> suggestions.add(suggestion(source, Reason.UNCOVERED_DOCUMENT, taken)));
        }
        return suggestions;
    }

    private static boolean shippable(Source source) {
        return source.missingDocInfoIds().isEmpty() && !source.documents().isEmpty();
    }

    private static Suggestion suggestion(Source source, Reason reason, Set<String> taken) {
        String toolName = uniqueToolName(KnowledgeContribution.defaultToolName(source.name()), taken);
        taken.add(toolName);
        ShipRequest.Knowledge knowledge = new ShipRequest.Knowledge(source.sourceId(), toolName,
                KnowledgeContribution.defaultDescription(source), null);
        return new Suggestion(knowledge, source.name(), reason, source.documents().size(),
                KnowledgeSearchService.llmStages(source.pipeline()));
    }

    static String uniqueToolName(String base, Set<String> taken) {
        String candidate = base;
        for (int i = 2; taken.contains(candidate); i++) {
            String suffix = "_" + i;
            String stem = base.length() + suffix.length() > KnowledgeContribution.MAX_TOOL_NAME_LENGTH
                    ? base.substring(0, KnowledgeContribution.MAX_TOOL_NAME_LENGTH - suffix.length())
                    : base;
            candidate = stem + suffix;
        }
        return candidate;
    }

    private List<VectorStoreDocumentInfo> knowledgeDocuments() {
        OfflineEtlPipelineService documents = this.documentsProvider.getIfAvailable();
        return documents == null ? List.of() : documents.getVisibleDocumentList();
    }

}
