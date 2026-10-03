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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springaicommunity.playground.config.MdcIdentityFilter;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springaicommunity.playground.service.identity.UserIdentityService;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class ObservabilityCollector implements ObservationHandler<Observation.Context> {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityCollector.class);

    public static final String ROOT_SPAN_NAME = "spring.ai.chat.client";
    public static final String CHAT_MODEL_SPAN_NAME = "gen_ai.client.operation";
    public static final String TOOL_SPAN_NAME = "spring.ai.tool";
    public static final String ADVISOR_SPAN_NAME = "spring.ai.advisor";
    public static final String VECTOR_STORE_SPAN_NAME = "db.vector.client.operation";
    public static final String TOOL_CALL_ID_ATTR = "spring.ai.tool.call.id";

    private static final int MIN_MODEL_ID_LENGTH = 3;
    private static final String EMBEDDING_OPERATION = "embedding";
    private static final String CTX_START_NS = "obs.startNs";
    private static final String CTX_START_EPOCH_MS = "obs.startEpochMs";
    private static final String CTX_STOPPED = "obs.stopped";
    private static final String CTX_RETRIEVED = "obs.retrieved";
    private static final String CTX_TRACE_ID = "obs.traceId";
    private static final String CTX_SPAN_ID = "obs.spanId";
    private static final String CTX_PARENT_SPAN_ID = "obs.parentSpanId";
    private static final String CTX_CONVERSATION_ID = "obs.conversationId";
    private static final String CTX_USER_MESSAGE_ID = "obs.userMessageId";
    private static final String CTX_USER_ID = "obs.userId";
    private static final String CTX_SESSION_ID = "obs.sessionId";

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";

    private final ObservabilityRingBuffer buffer;
    private final ObjectProvider<ObservabilityPersistenceService> persistenceProvider;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<UserIdentityService> userIdentityProvider;
    private volatile String cachedUserId;
    private final ObservabilityProperties props;
    private final VectorOperationLog vectorOperationLog;

    private final Map<String, TraceBuilder> active = new ConcurrentHashMap<>();
    private final AtomicLong fallbackTraceCounter = new AtomicLong();
    private final Set<String> finalizedTraceIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(512, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 4096;
                }
            }));

    public ObservabilityCollector(ObservabilityRingBuffer buffer,
            ObjectProvider<ObservabilityPersistenceService> persistenceProvider,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<UserIdentityService> userIdentityProvider,
            ObservabilityProperties props, VectorOperationLog vectorOperationLog) {
        this.vectorOperationLog = vectorOperationLog;
        this.buffer = buffer;
        this.persistenceProvider = persistenceProvider;
        this.tracerProvider = tracerProvider;
        this.userIdentityProvider = userIdentityProvider;
        this.props = props;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        if (context == null) return false;
        String cls = context.getClass().getName();
        if (cls.startsWith("org.springframework.ai.")) return true;
        String name = context.getName();
        if (name == null) return false;
        return name.startsWith("spring.ai.")
                || CHAT_MODEL_SPAN_NAME.equals(name)
                || VECTOR_STORE_SPAN_NAME.equals(name);
    }

    @Override
    public void onStop(Observation.Context context) {
        if (context.get(CTX_STOPPED) != null) return;
        context.put(CTX_STOPPED, Boolean.TRUE);
        captureSpanIds(context);
        long startNs = (long) Optional.ofNullable(context.get(CTX_START_NS))
                .orElse(System.nanoTime());
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        long endEpochMs = Instant.now().toEpochMilli();
        long startEpochMs = (long) Optional.ofNullable(context.get(CTX_START_EPOCH_MS))
                .orElse(endEpochMs - durationMs);

        Map<String, String> attrs = extractAttributes(context);
        Throwable err = context.getError();
        if (err != null) {
            attrs.putIfAbsent("error.type", err.getClass().getSimpleName());
            String msg = err.getMessage();
            if (msg != null && !msg.isBlank()) {
                attrs.putIfAbsent("error.message", msg.length() > 200 ? msg.substring(0, 200) : msg);
            }
        }
        String traceId = (String) context.get(CTX_TRACE_ID);
        String spanId = (String) context.get(CTX_SPAN_ID);
        String parentSpanId = (String) context.get(CTX_PARENT_SPAN_ID);

        if (traceId == null || traceId.isBlank()) {
            traceId = activeTraceOfConversation(attrs.get("conversation.id")).orElseGet(this::nextLocalTraceId);
        }
        if (spanId == null || spanId.isBlank()) {
            spanId = UUID.randomUUID().toString();
        }

        String spanName = context.getName();
        if (spanName == null) spanName = context.getContextualName();
        if (spanName == null) spanName = simpleClassNameFallback(context);

        SpanRecord span = new SpanRecord(spanId, parentSpanId, spanName,
                startEpochMs, durationMs, errorStatus(context), attrs);

        boolean isRoot = isRoot(context);
        recordVectorOperation(context, span);

        if (!isRoot && finalizedTraceIds.contains(traceId)) {
            TraceRecord lateTrace = lateSpanTrace(traceId, span);
            TraceRecord merged = buffer.add(lateTrace);
            if (merged != lateTrace) {
                persistIfEnabled(merged);
            }
            return;
        }

        if (TOOL_SPAN_NAME.equals(span.name()) && !active.containsKey(traceId)) {
            finalizedTraceIds.add(traceId);
            persistIfEnabled(buffer.add(lateSpanTrace(traceId, span)));
            return;
        }

        TraceBuilder builder = active.computeIfAbsent(traceId, id -> new TraceBuilder(id, null));
        builder.addSpan(span, props.getMaxSpansPerTrace());

        if (isRoot) {
            active.remove(traceId);
            finalizedTraceIds.add(traceId);
            persistIfEnabled(buffer.add(builder.finalizeTrace(span, retrievedDuring(context, span, endEpochMs))));
        }
    }

    private void recordVectorOperation(Observation.Context context, SpanRecord span) {
        if (!VECTOR_STORE_SPAN_NAME.equals(span.name())) return;
        Map<String, String> attributes = span.attributes();
        if (ToolIndexVectorObservationConvention.TOOL_INDEX_ROLE.equals(
                attributes.get(ToolIndexVectorObservationConvention.STORE_ROLE_KEY))) {
            return;
        }
        Integer returnedDocuments = null;
        String queryText = null;
        if (context instanceof VectorStoreObservationContext vectorContext) {
            if (VectorStoreService.isPlaceholderQuery(vectorContext.getQueryRequest())) return;
            if (vectorContext.getQueryResponse() != null) {
                returnedDocuments = vectorContext.getQueryResponse().size();
            }
            if (vectorContext.getQueryRequest() != null) {
                queryText = vectorContext.getQueryRequest().getQuery();
            }
        }
        Optional<Observation.Context> chatRoot = enclosingChatRoot(context);
        String conversationId = Optional.ofNullable(blankToNull(attributes.get("conversation.id")))
                .or(() -> chatRoot.map(root -> (String) root.get(CTX_CONVERSATION_ID))).orElse(null);
        VectorOperationLog.VectorOperation operation = new VectorOperationLog.VectorOperation(
                span.startEpochMs(), span.durationMs(), attributes.getOrDefault("db.operation.name", "?"),
                attributes.getOrDefault("db.system", "unknown"), parseLong(attributes.get("db.vector.query.top_k")),
                parseDouble(attributes.get("db.vector.query.similarity_threshold")), returnedDocuments,
                conversationId, !"OK".equals(span.status()));
        if (operation.isQuery()) chatRoot.ifPresent(root -> root.put(CTX_RETRIEVED, Boolean.TRUE));
        logger.debug("vector.op operation={} conversation={} insideChatTurn={}", operation.operation(),
                conversationId, chatRoot.isPresent());
        this.vectorOperationLog.record(operation, queryText);
    }

    private Optional<Observation.Context> enclosingChatRoot(Observation.Context context) {
        Observation.Context outermostChatClient = null;
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            if (parent.getContextView() instanceof Observation.Context parentContext
                    && isChatClient(parentContext)) {
                outermostChatClient = parentContext;
            }
            parent = parent.getContextView().getParentObservation();
        }
        return Optional.ofNullable(outermostChatClient);
    }

    private boolean retrievedDuring(Observation.Context rootContext, SpanRecord rootSpan, long rootEndEpochMs) {
        if (rootContext.get(CTX_RETRIEVED) != null) return true;
        Map<String, String> rootAttributes = rootSpan.attributes();
        String conversationId = rootAttributes.getOrDefault("conversation.id",
                rootAttributes.get("spring.ai.chat.client.conversation.id"));
        return this.vectorOperationLog.hasQueryFor(conversationId, rootSpan.startEpochMs(), rootEndEpochMs);
    }

    private static Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void persistIfEnabled(TraceRecord trace) {
        ObservabilityPersistenceService persist = persistenceProvider.getIfAvailable();
        if (persist != null && props.isPersist()) {
            persist.saveAsync(trace);
        }
    }

    private TraceRecord lateSpanTrace(String traceId, SpanRecord span) {
        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> serverNames = new LinkedHashSet<>();
        Map<String, String> a = span.attributes() == null ? Map.of() : span.attributes();
        boolean toolSpan = TOOL_SPAN_NAME.equals(span.name());
        if (toolSpan) {
            String toolName = toolNameOf(a);
            if (toolName != null && !toolName.isBlank()) toolNames.add(toolName);
            String mcpServer = a.get("saip.mcp.server");
            if (mcpServer != null && !mcpServer.isBlank()) serverNames.add(mcpServer);
        }
        List<SpanRecord> spans = new ArrayList<>();
        spans.add(span);
        return new TraceRecord(traceId,
                blankToNull(a.get("conversation.id")),
                blankToNull(a.get("user_message.id")),
                null, null,
                span.startEpochMs(), span.durationMs(), errorStatusOf(span),
                null, null, null, null,
                toolSpan, toolSpan ? 1 : 0, false,
                blankToNull(a.get("user.id")), blankToNull(a.get("session.id")),
                toolNames, serverNames,
                spans, new HashMap<>());
    }

    static String collapseRepeatedModelId(String modelId) {
        if (modelId == null) return null;
        int length = modelId.length();
        for (int unitLength = MIN_MODEL_ID_LENGTH; unitLength <= length / 2; unitLength++) {
            if (length % unitLength != 0) continue;
            String unit = modelId.substring(0, unitLength);
            if (unit.repeat(length / unitLength).equals(modelId)) return unit;
        }
        return modelId;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String errorStatusOf(SpanRecord span) {
        return TraceRecord.STATUS_ERROR.equals(span.status()) ? TraceRecord.STATUS_ERROR : TraceRecord.STATUS_OK;
    }

    private static String toolNameOf(Map<String, String> attrs) {
        String name = attrs.get("gen_ai.tool.name");
        if (name == null || name.isBlank()) name = attrs.get("spring.ai.tool.definition.name");
        return name;
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(CTX_START_NS, System.nanoTime());
        context.put(CTX_START_EPOCH_MS, Instant.now().toEpochMilli());
        captureSpanIds(context);
        captureIdentity(context);
        inheritOrStartTrace(context);
        if (isRoot(context)) {
            String traceId = (String) context.get(CTX_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                String conversationId = (String) context.get(CTX_CONVERSATION_ID);
                active.computeIfAbsent(traceId, id -> new TraceBuilder(id, conversationId));
            }
        }
    }

    private void inheritOrStartTrace(Observation.Context context) {
        if (context.get(CTX_TRACE_ID) != null) return;
        Optional<Observation.Context> chatRoot = enclosingChatRoot(context);
        if (chatRoot.isPresent()) {
            putIfPresent(context, CTX_TRACE_ID, (String) chatRoot.get().get(CTX_TRACE_ID));
            return;
        }
        if (isChatClient(context)) context.put(CTX_TRACE_ID, nextLocalTraceId());
    }

    private String nextLocalTraceId() {
        return "local-" + fallbackTraceCounter.incrementAndGet();
    }

    private Optional<String> activeTraceOfConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        return active.values().stream()
                .filter(builder -> conversationId.equals(builder.conversationId))
                .max(Comparator.comparingLong(builder -> builder.lastTouchedMs))
                .map(builder -> builder.traceId);
    }

    private boolean isRoot(Observation.Context context) {
        return isChatClient(context) && enclosingChatRoot(context).isEmpty();
    }

    private static boolean isChatClient(Observation.Context context) {
        return ROOT_SPAN_NAME.equals(context.getName())
                || ROOT_SPAN_NAME.equals(context.getContextualName())
                || context.getClass().getName().endsWith("ChatClientObservationContext");
    }

    private void captureIdentity(Observation.Context context) {
        putIfPresent(context, CTX_CONVERSATION_ID, MDC.get(ChatService.MDC_CONVERSATION_ID));
        putIfPresent(context, CTX_USER_MESSAGE_ID, MDC.get(ChatService.MDC_USER_MESSAGE_ID));
        String userId = MDC.get(MdcIdentityFilter.USER_ID);
        if (userId == null || userId.isBlank()) {
            userId = fallbackUserId();
        }
        putIfPresent(context, CTX_USER_ID, userId);
        putIfPresent(context, CTX_SESSION_ID, MDC.get(MdcIdentityFilter.SESSION_ID));
    }

    private String fallbackUserId() {
        String cached = this.cachedUserId;
        if (cached != null) return cached;
        UserIdentityService identity = userIdentityProvider.getIfAvailable();
        if (identity == null) return null;
        this.cachedUserId = identity.currentUserId();
        return this.cachedUserId;
    }

    private static void putIfPresent(Observation.Context context, String key, String value) {
        if (value != null && !value.isBlank()) {
            context.put(key, value);
        }
    }

    @Override
    public void onError(Observation.Context context) {
    }

    @Scheduled(fixedDelayString = "${spring.ai.playground.observability.active-trace-cleanup-ms:60000}")
    public void cleanupStaleActiveTraces() {
        evictActiveOlderThan(Instant.now().toEpochMilli() - props.getActiveTraceTtlSeconds() * 1000L);
    }

    int evictActiveOlderThan(long cutoffEpochMs) {
        int before = active.size();
        active.entrySet().removeIf(e -> e.getValue().lastTouchedMs < cutoffEpochMs);
        int evicted = before - active.size();
        if (evicted > 0) {
            logger.debug("Evicted {} stale active trace builders (cutoff={}ms)", evicted, cutoffEpochMs);
        }
        return evicted;
    }

    int activeSize() {
        return active.size();
    }

    private void captureSpanIds(Observation.Context context) {
        if (context.get(CTX_TRACE_ID) != null) {
            return;
        }
        Tracer tracer = tracerProvider.getIfAvailable();
        Span current = tracer == null ? null : tracer.currentSpan();
        if (current != null) {
            context.put(CTX_TRACE_ID, current.context().traceId());
            context.put(CTX_SPAN_ID, current.context().spanId());
            String parent = current.context().parentId();
            if (parent != null) {
                context.put(CTX_PARENT_SPAN_ID, parent);
            }
            return;
        }
        String mdcTraceId = MDC.get(MDC_TRACE_ID);
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            context.put(CTX_TRACE_ID, mdcTraceId);
            String mdcSpanId = MDC.get(MDC_SPAN_ID);
            if (mdcSpanId != null && !mdcSpanId.isBlank()) {
                context.put(CTX_SPAN_ID, mdcSpanId);
            }
        }
    }

    private Map<String, String> extractAttributes(Observation.Context context) {
        Map<String, String> attrs = new HashMap<>();
        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            putIfNotNull(attrs, kv);
        }
        for (KeyValue kv : context.getHighCardinalityKeyValues()) {
            putIfNotNull(attrs, kv);
        }
        String conv = identity(context, CTX_CONVERSATION_ID, ChatService.MDC_CONVERSATION_ID);
        if (conv != null) {
            attrs.putIfAbsent("conversation.id", conv);
        }
        String msg = identity(context, CTX_USER_MESSAGE_ID, ChatService.MDC_USER_MESSAGE_ID);
        if (msg != null) {
            attrs.putIfAbsent("user_message.id", msg);
        }
        String userId = identity(context, CTX_USER_ID, MdcIdentityFilter.USER_ID);
        if (userId != null) {
            attrs.putIfAbsent("user.id", userId);
        }
        String sessionId = identity(context, CTX_SESSION_ID, MdcIdentityFilter.SESSION_ID);
        if (sessionId != null) {
            attrs.putIfAbsent("session.id", sessionId);
        }
        enrichWithChatModelMessages(context, attrs);
        enrichWithChatClientResponse(context, attrs);
        return attrs;
    }

    private void enrichWithChatClientResponse(Observation.Context context, Map<String, String> attrs) {
        if (!(context instanceof ChatClientObservationContext ccoc)) return;
        try {
            ChatClientResponse ccr = ccoc.getResponse();
            if (ccr == null) return;
            ChatResponse chatResponse = ccr.chatResponse();
            if (chatResponse == null) return;
            ChatResponseMetadata meta = chatResponse.getMetadata();
            if (meta == null) return;
            String model = meta.getModel();
            if (model != null && !model.isBlank()) {
                attrs.putIfAbsent("gen_ai.response.model", collapseRepeatedModelId(model));
            }
            Usage usage = meta.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) {
                    attrs.putIfAbsent("gen_ai.usage.input_tokens", usage.getPromptTokens().toString());
                }
                if (usage.getCompletionTokens() != null) {
                    attrs.putIfAbsent("gen_ai.usage.output_tokens", usage.getCompletionTokens().toString());
                }
                if (usage.getTotalTokens() != null) {
                    attrs.putIfAbsent("gen_ai.usage.total_tokens", usage.getTotalTokens().toString());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract usage from ChatClientObservationContext", e);
        }
    }

    private void enrichWithChatModelMessages(Observation.Context context, Map<String, String> attrs) {
        if (!props.isCapturePromptContent()) return;
        if (!(context instanceof ChatModelObservationContext cmoc)) return;
        int max = Math.max(256, props.getMaxPromptContentBytes());
        int maxMessages = Math.max(1, props.getMaxCapturedMessagesPerSpan());
        try {
            Prompt prompt = cmoc.getRequest();
            if (prompt != null && prompt.getInstructions() != null) {
                List<Message> messages = prompt.getInstructions();
                attrs.putIfAbsent("gen_ai.prompt.count", String.valueOf(messages.size()));
                int captureCount = Math.min(messages.size(), maxMessages);
                if (captureCount < messages.size()) {
                    attrs.putIfAbsent("gen_ai.prompt.truncated_messages",
                            String.valueOf(messages.size() - captureCount));
                }
                for (int i = 0; i < captureCount; i++) {
                    Message m = messages.get(i);
                    if (m == null) continue;
                    String role = roleOf(m.getMessageType());
                    attrs.putIfAbsent("gen_ai.prompt." + i + ".role", role);
                    String text = m.getText();
                    if (text != null && !text.isEmpty()) {
                        attrs.putIfAbsent("gen_ai.prompt." + i + ".content", truncate(text, max));
                    }
                }
            }
            ChatResponse response = cmoc.getResponse();
            if (response != null) {
                Generation result = response.getResult();
                if (result != null) {
                    AssistantMessage out = result.getOutput();
                    if (out != null) {
                        attrs.putIfAbsent("gen_ai.completion.0.role", "assistant");
                        String text = out.getText();
                        if (text != null && !text.isEmpty()) {
                            attrs.putIfAbsent("gen_ai.completion.0.content", truncate(text, max));
                        }
                        if (out.hasToolCalls()) {
                            String calls = out.getToolCalls().stream()
                                    .limit(8)
                                    .map(tc -> tc.name() + "(" + truncate(tc.arguments(), 256) + ")")
                                    .collect(Collectors.joining(" · "));
                            attrs.putIfAbsent("gen_ai.completion.0.tool_calls", calls);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to capture prompt/completion content", e);
        }
    }

    private static String roleOf(MessageType type) {
        if (type == null) return "unknown";
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + " …(truncated " + (s.length() - max) + " chars)";
    }

    private static String identity(Observation.Context context, String ctxKey, String mdcKey) {
        Object captured = context.get(ctxKey);
        if (captured instanceof String s && !s.isBlank()) {
            return s;
        }
        return MDC.get(mdcKey);
    }

    private void putIfNotNull(Map<String, String> attrs, KeyValue kv) {
        if (kv == null || kv.getKey() == null || kv.getValue() == null) return;
        attrs.put(kv.getKey(), kv.getValue());
    }

    private String errorStatus(Observation.Context context) {
        return context.getError() == null ? TraceRecord.STATUS_OK : TraceRecord.STATUS_ERROR;
    }

    private String simpleClassNameFallback(Observation.Context context) {
        String simple = context.getClass().getSimpleName();
        if (simple.endsWith("ObservationContext")) simple = simple.substring(0, simple.length() - "ObservationContext".length());
        else if (simple.endsWith("Context")) simple = simple.substring(0, simple.length() - "Context".length());
        return simple;
    }

    private static final class TraceBuilder {
        private final String traceId;
        private final String conversationId;
        private final List<SpanRecord> spans = new ArrayList<>();
        private volatile long lastTouchedMs = Instant.now().toEpochMilli();

        TraceBuilder(String traceId, String conversationId) {
            this.traceId = traceId;
            this.conversationId = conversationId;
        }

        synchronized void addSpan(SpanRecord span, int maxSpans) {
            lastTouchedMs = Instant.now().toEpochMilli();
            if (spans.size() >= maxSpans) return;
            if (span.spanId() != null) {
                for (SpanRecord existing : spans) {
                    if (span.spanId().equals(existing.spanId())) return;
                }
            }
            spans.add(span);
        }

        synchronized TraceRecord finalizeTrace(SpanRecord rootSpan, boolean retrievedDuringTrace) {
            Map<String, String> rootAttrs = rootSpan.attributes();
            String conversationId = rootAttrs.getOrDefault("conversation.id",
                    rootAttrs.get("spring.ai.chat.client.conversation.id"));
            String userMessageId = rootAttrs.get("user_message.id");
            String userId = rootAttrs.get("user.id");
            String sessionId = rootAttrs.get("session.id");

            String provider = null;
            String model = null;
            Long inputTokens = null;
            Long outputTokens = null;
            Long totalTokens = null;
            Long turnInputTokens = null;
            Long turnOutputTokens = null;
            Long turnTotalTokens = null;
            String finishReason = null;
            int toolCallCount = 0;
            boolean hasRag = retrievedDuringTrace;
            Set<String> toolNames = new LinkedHashSet<>();
            Set<String> serverNames = new LinkedHashSet<>();

            for (SpanRecord s : spans) {
                Map<String, String> a = s.attributes();
                if (isChatModelCall(s) || ROOT_SPAN_NAME.equals(s.name())) {
                    if (provider == null) provider = nonSentinel(a.get("gen_ai.system"));
                    if (model == null) {
                        String responseModel = collapseRepeatedModelId(nonSentinel(a.get("gen_ai.response.model")));
                        String requestModel = nonSentinel(a.get("gen_ai.request.model"));
                        model = TraceRecord.firstNonBlank(responseModel, requestModel);
                    }
                    if (isChatModelCall(s)) {
                        inputTokens = sumLong(inputTokens, a.get("gen_ai.usage.input_tokens"));
                        outputTokens = sumLong(outputTokens, a.get("gen_ai.usage.output_tokens"));
                        totalTokens = sumLong(totalTokens, a.get("gen_ai.usage.total_tokens"));
                    } else if (s == rootSpan) {
                        turnInputTokens = sumLong(null, a.get("gen_ai.usage.input_tokens"));
                        turnOutputTokens = sumLong(null, a.get("gen_ai.usage.output_tokens"));
                        turnTotalTokens = sumLong(null, a.get("gen_ai.usage.total_tokens"));
                    }
                    if (finishReason == null) finishReason = a.get("gen_ai.response.finish_reasons");
                } else if (TOOL_SPAN_NAME.equals(s.name())) {
                    toolCallCount++;
                    String toolName = toolNameOf(a);
                    if (toolName != null && !toolName.isBlank()) toolNames.add(toolName);
                    String mcpServer = a.get("saip.mcp.server");
                    if (mcpServer != null && !mcpServer.isBlank()) serverNames.add(mcpServer);
                } else if (isDocumentStoreOperation(s)) {
                    hasRag = true;
                }
            }

            String status = rootSpan.status();
            for (SpanRecord s : spans) {
                if (TraceRecord.STATUS_ERROR.equals(s.status())) {
                    status = TraceRecord.STATUS_ERROR;
                    break;
                }
            }

            return new TraceRecord(
                    traceId,
                    conversationId,
                    userMessageId,
                    provider,
                    model,
                    rootSpan.startEpochMs(),
                    rootSpan.durationMs(),
                    status,
                    turnInputTokens != null ? turnInputTokens : inputTokens,
                    turnOutputTokens != null ? turnOutputTokens : outputTokens,
                    turnTotalTokens != null ? turnTotalTokens : totalTokens,
                    finishReason,
                    toolCallCount > 0,
                    toolCallCount,
                    hasRag,
                    userId,
                    sessionId,
                    toolNames,
                    serverNames,
                    new ArrayList<>(spans),
                    rootAttrs);
        }

        private static boolean isChatModelCall(SpanRecord span) {
            return CHAT_MODEL_SPAN_NAME.equals(span.name())
                    && !EMBEDDING_OPERATION.equals(span.attributes().get("gen_ai.operation.name"));
        }

        private static boolean isDocumentStoreOperation(SpanRecord span) {
            return VECTOR_STORE_SPAN_NAME.equals(span.name())
                    && !ToolIndexVectorObservationConvention.TOOL_INDEX_ROLE.equals(
                            span.attributes().get(ToolIndexVectorObservationConvention.STORE_ROLE_KEY));
        }

        private static String nonSentinel(String s) {
            if (s == null || s.isBlank()) return null;
            String lower = s.toLowerCase();
            if (lower.equals("none") || lower.equals("unknown") || lower.equals("null")) return null;
            return s;
        }

        private Long sumLong(Long current, String raw) {
            if (raw == null || raw.isBlank()) return current;
            try {
                long v = Long.parseLong(raw.trim());
                return current == null ? v : current + v;
            } catch (NumberFormatException e) {
                return current;
            }
        }
    }
}
