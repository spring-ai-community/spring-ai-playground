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
package org.springaicommunity.playground.service.vectorstore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class VectorStoreServiceTest {
    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private VectorStoreService vectorStoreService;


    @Test
    public void testSearchWithPromptAndFilter() {
        vectorStoreService.setVectorStoreOption(new VectorStoreService.SearchRequestOption(0.7, 5));
        String userPromptText = "test prompt";
        String filterExpression = "a == 'b'";
        List<Document> expectedResult = List.of(new Document("id", "text", Map.of("a", "b")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(expectedResult);

        Collection<Document> result = vectorStoreService.search(userPromptText, filterExpression);

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        assertSame(expectedResult, result);
    }

    @Test
    public void testSearchWithSearchRequest() {
        List<Document> expectedResult = List.of(new Document("id", "text", Map.of()));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(expectedResult);

        Collection<Document> result = vectorStoreService.search("test prompt", "");

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        assertSame(expectedResult, result);
    }

    @Test
    public void searchExcludesTheGivenDocInfoIdsAlongsideTheUserFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ArgumentCaptor<SearchRequest> searchRequestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        vectorStoreService.search("test prompt", "a == 'b'", List.of("docInfoId-hidden"));
        vectorStoreService.search("test prompt", "", List.of("docInfoId-hidden"));
        vectorStoreService.search("test prompt", "", List.of());

        verify(vectorStore, times(3)).similaritySearch(searchRequestCaptor.capture());
        List<SearchRequest> searchRequests = searchRequestCaptor.getAllValues();
        Filter.Expression combined = searchRequests.get(0).getFilterExpression();
        assertEquals(Filter.ExpressionType.AND, combined.type());
        assertEquals(Filter.ExpressionType.EQ, ((Filter.Expression) combined.left()).type());
        assertEquals(Filter.ExpressionType.NIN, ((Filter.Expression) combined.right()).type());
        Filter.Expression exclusionOnly = searchRequests.get(1).getFilterExpression();
        assertEquals(Filter.ExpressionType.NIN, exclusionOnly.type());
        assertEquals(List.of("docInfoId-hidden"), ((Filter.Value) exclusionOnly.right()).value());
        assertNull(searchRequests.get(2).getFilterExpression());
    }

    @Test
    public void testAddDocument() {
        Document document = new Document("id", "text", Map.of());

        Document result = vectorStoreService.add(List.of(document)).getFirst();

        verify(vectorStore).add(List.of(document));
        assertSame(document, result);
    }

    @Test
    public void testUpdateDocument() {
        Document document = new Document("id", "text", Map.of());

        Document result = vectorStoreService.update(document);

        verify(vectorStore).delete(List.of("id"));
        verify(vectorStore).add(List.of(document));
        assertSame(document, result);
    }

    @Test
    public void testDeleteDocuments() {
        List<String> documentIds = List.of("id1", "id2");

        vectorStoreService.delete(documentIds);

        verify(vectorStore).delete(documentIds);
    }

    @Test
    public void testAddDocuments() {
        List<Document> documents = List.of(
                new Document("id1", "text1", Map.of()),
                new Document("id2", "text2", Map.of())
        );

        vectorStoreService.add(documents);

        verify(vectorStore).add(documents);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatOriginDocumentSkipsDefaultPipelineCreation() {
        RagPipelineService ragPipelineService = mock(RagPipelineService.class);
        ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider = mock(ObjectProvider.class);
        when(persistenceProvider.getObject()).thenReturn(mock(VectorStoreDocumentPersistenceService.class));
        VectorStoreService service = new VectorStoreService(mock(EmbeddingModel.class), mock(VectorStore.class),
                mock(ApplicationContext.class), persistenceProvider, ragPipelineService, 0.35, 4);

        service.add(new VectorStoreDocumentInfo("chat-doc", "attached.txt", null, 0L, 0L, "attached.txt", "", true,
                List::of));
        verify(ragPipelineService, never()).ensureDefaultPipeline(any(), any());

        service.add(new VectorStoreDocumentInfo("kb-doc", "curated.txt", 0L, 0L, "curated.txt", "", List::of));
        verify(ragPipelineService).ensureDefaultPipeline(any(), any());
    }

    @Test
    void searchAllRequestExcludesChatOriginDocuments() {
        SearchRequest unfiltered = VectorStoreService.searchAllRequest(List.of());
        assertNull(unfiltered.getFilterExpression());
        assertEquals(VectorStoreService.ALL_SEARCH_REQUEST_OPTION.topK(), unfiltered.getTopK());

        Filter.Expression exclusion = VectorStoreService.searchAllRequest(List.of("chat-doc")).getFilterExpression();
        assertEquals(Filter.ExpressionType.NIN, exclusion.type());
        assertEquals(new Filter.Key(VectorStoreService.DOC_INFO_ID), exclusion.left());
    }

}
