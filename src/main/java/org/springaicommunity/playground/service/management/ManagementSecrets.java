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
package org.springaicommunity.playground.service.management;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ManagementSecrets {

    public static final String MASK = "***";

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(authorization|token|secret|passw(or)?d|api[-_]?key|credential|cookie).*");
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    private ManagementSecrets() {
    }

    public static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME.matcher(name).matches();
    }

    public static boolean isEnvReference(String value) {
        return value != null && ENV_REFERENCE.matcher(value).find();
    }

    public static void requireReference(String what, String name, String value) {
        if (!isSensitiveName(name) || value == null || value.isBlank() || isEnvReference(value)) {
            return;
        }
        throw new IllegalArgumentException(what + " '" + name + "' looks like a secret but holds a literal value. "
                + "Secrets must not pass through this interface: set an environment variable on the machine that "
                + "runs this app and reference it as ${ENV_NAME} instead.");
    }

    public static String maskText(String text) {
        return text == null ? null : BEARER_VALUE.matcher(text).replaceAll("$1 " + MASK);
    }

    public static Map<String, Object> maskArguments(Map<String, Object> arguments) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (arguments == null) {
            return masked;
        }
        arguments.forEach((name, value) -> masked.put(name, maskValue(name, value)));
        return masked;
    }

    @SuppressWarnings("unchecked")
    private static Object maskValue(String name, Object value) {
        if (isSensitiveName(name) && value instanceof String text && !isEnvReference(text)) {
            return MASK;
        }
        if (value instanceof Map<?, ?> nested) {
            return maskArguments((Map<String, Object>) nested);
        }
        if (value instanceof List<?> items) {
            return items.stream().map(item -> maskValue(name, item)).toList();
        }
        if (value instanceof String text) {
            return maskText(text);
        }
        return value;
    }

}
