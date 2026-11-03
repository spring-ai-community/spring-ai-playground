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

import org.springframework.ai.template.TemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChatSystemPromptTemplateRenderer implements TemplateRenderer {

    public enum VariableType { TEXT, MULTILINE, NUMBER, SELECT, LIST }

    public record VariableSpec(String name, VariableType type, List<String> options, Double min, Double max,
            Integer limit, String defaultValue) {
    }

    private static final Pattern VARIABLE_TOKEN_PATTERN =
            Pattern.compile("\\{\\{(\\w+)(?::(\\w+)(?:\\(([^)]*)\\))?)?(?:=([^}]*))?\\}\\}");

    public String token(VariableSpec spec) {
        StringBuilder token = new StringBuilder("{{").append(spec.name());
        List<String> options = spec.options() == null ? List.of() : spec.options();
        switch (spec.type() == null ? VariableType.TEXT : spec.type()) {
            case MULTILINE -> token.append(":multiline");
            case NUMBER -> {
                token.append(":number");
                if (spec.min() != null || spec.max() != null)
                    token.append('(').append(numberText(spec.min())).append(',').append(numberText(spec.max()))
                            .append(')');
            }
            case SELECT -> token.append(":select(").append(String.join(",", options)).append(')');
            case LIST -> {
                token.append(":list");
                List<String> parts = new ArrayList<>();
                if (!options.isEmpty()) parts.add(String.join(",", options));
                if (spec.limit() != null) parts.add("max=" + spec.limit());
                if (!parts.isEmpty()) token.append('(').append(String.join(", ", parts)).append(')');
            }
            default -> { }
        }
        if (spec.defaultValue() != null && !spec.defaultValue().isBlank())
            token.append('=').append(spec.defaultValue().trim());
        return token.append("}}").toString();
    }

    public static String numberText(Double value) {
        if (value == null) return "";
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    public List<String> variables(String template) {
        return variableSpecs(template).stream().map(VariableSpec::name).toList();
    }

    public List<VariableSpec> variableSpecs(String template) {
        if (template == null) return List.of();
        Map<String, VariableSpec> ordered = new LinkedHashMap<>();
        Matcher matcher = VARIABLE_TOKEN_PATTERN.matcher(template);
        while (matcher.find()) {
            ordered.putIfAbsent(matcher.group(1),
                    toSpec(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        return List.copyOf(ordered.values());
    }

    public String render(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) return "";
        Matcher matcher = VARIABLE_TOKEN_PATTERN.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values == null ? null : values.get(matcher.group(1));
            if (value == null || value.isBlank()) {
                String fallback = matcher.group(4);
                value = fallback == null ? "" : fallback;
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    @Override
    public String apply(String template, Map<String, ?> variables) {
        Map<String, String> values = new LinkedHashMap<>();
        if (variables != null) variables.forEach((name, value) -> values.put(name, String.valueOf(value)));
        return render(template, values);
    }

    private VariableSpec toSpec(String name, String rawType, String args, String defaultValue) {
        VariableType type = parseType(rawType);
        List<String> parts = args == null ? List.of()
                : Arrays.stream(args.split(",")).map(String::trim).toList();
        return switch (type) {
            case NUMBER -> new VariableSpec(name, type, List.of(), numberAt(parts, 0), numberAt(parts, 1), null,
                    defaultValue);
            case SELECT -> new VariableSpec(name, type, parts.stream().filter(part -> !part.isEmpty()).toList(),
                    null, null, null, defaultValue);
            case LIST -> listSpec(name, parts, defaultValue);
            default -> new VariableSpec(name, type, List.of(), null, null, null, defaultValue);
        };
    }

    private VariableType parseType(String rawType) {
        if (rawType == null) return VariableType.TEXT;
        try {
            return VariableType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VariableType.TEXT;
        }
    }

    private VariableSpec listSpec(String name, List<String> parts, String defaultValue) {
        Integer limit = null;
        List<String> options = new ArrayList<>();
        for (String part : parts) {
            if (part.toLowerCase(Locale.ROOT).startsWith("max=")) {
                try {
                    limit = Integer.valueOf(part.substring(4).trim());
                } catch (NumberFormatException e) {
                    limit = null;
                }
            } else if (!part.isEmpty()) {
                options.add(part);
            }
        }
        return new VariableSpec(name, VariableType.LIST, List.copyOf(options), null, null, limit, defaultValue);
    }

    private Double numberAt(List<String> parts, int index) {
        if (index >= parts.size() || parts.get(index).isEmpty()) return null;
        try {
            return Double.valueOf(parts.get(index));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
