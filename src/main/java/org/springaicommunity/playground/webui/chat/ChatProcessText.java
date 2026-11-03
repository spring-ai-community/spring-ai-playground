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
package org.springaicommunity.playground.webui.chat;

import org.springaicommunity.playground.service.chat.ChatActionBlocks;
import org.springaicommunity.playground.service.chat.ChatTurnRecorder;
import org.springaicommunity.playground.service.chat.ChatTurnRecorder.TokenUsage;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class ChatProcessText {

    private ChatProcessText() {
    }

    static String formatDuration(long durationMs) {
        return durationMs < 1000 ? durationMs + "ms" : String.format(Locale.ROOT, "%.1fs", durationMs / 1000.0);
    }

    static String formatTokens(long promptTokens, long completionTokens) {
        return String.format(Locale.US, "%,d tokens (in %,d \u00b7 out %,d)", promptTokens + completionTokens,
                promptTokens, completionTokens);
    }

    static String joinExtra(String left, String right) {
        if (left == null || left.isEmpty()) return right;
        if (right == null || right.isEmpty()) return left;
        return left + " \u00b7 " + right;
    }

    static String tokensExtraOf(Map<String, Object> metadata, String promptKey, String completionKey) {
        if (metadata.get(promptKey) instanceof Number in && metadata.get(completionKey) instanceof Number out)
            return formatTokens(in.longValue(), out.longValue());
        return null;
    }

    static String ragExtra(int docCount, Collection<String> titles) {
        if (docCount < 0) return null;
        String countText = docCount + (docCount == 1 ? " doc" : " docs");
        if (Objects.isNull(titles) || titles.isEmpty()) return countText;
        return countText + " \u00b7 " + String.join(", ", titles);
    }

    static String mcpExtra(int callCount, Collection<String> toolNames) {
        if (callCount <= 0) return null;
        String callText = callCount + (callCount == 1 ? " call" : " calls");
        if (Objects.isNull(toolNames) || toolNames.isEmpty()) return callText;
        return callText + " \u00b7 " + String.join(", ", toolNames);
    }

    static String ragExtra(ChatTurnRecorder recorder) {
        return ragExtra(recorder.ragDocCount(), recorder.ragDocTitles());
    }

    static String mcpExtra(ChatTurnRecorder recorder) {
        return joinExtra(mcpExtra(recorder.mcpCallCount(), recorder.mcpToolNames()),
                tokensText(recorder.mcpUsage().orElse(null)));
    }

    static String thinkTokens(ChatTurnRecorder recorder) {
        return tokensText(recorder.thinkUsage().orElse(null));
    }

    static String appendActionBlocks(String text, Object blocks) {
        if (!(blocks instanceof List<?> list) || list.isEmpty()) return text;
        StringBuilder merged = new StringBuilder(Objects.isNull(text) ? "" : text);
        List<String> candidates = list.stream().map(String::valueOf).toList();
        for (String block : ChatActionBlocks.missingFrom(merged.toString(), candidates))
            merged.append("\n\n").append(block).append("\n");
        return merged.toString();
    }

    private static String tokensText(TokenUsage usage) {
        return usage == null ? null : formatTokens(usage.promptTokens(), usage.completionTokens());
    }
}
