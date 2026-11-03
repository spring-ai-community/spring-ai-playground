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

import org.springaicommunity.playground.service.util.EnvVarResolver;

import java.util.regex.Pattern;

final class BundleSecrets {

    private static final Pattern SECRET_NAME_PATTERN =
            Pattern.compile(".*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_SECRET_PATTERN = Pattern.compile("[A-Za-z0-9+/=_-]{20,}");
    private static final Pattern SECRET_HEADER_PATTERN = Pattern.compile(
            "authorization|.*(key|token|secret|password|credential|cookie).*", Pattern.CASE_INSENSITIVE);

    private BundleSecrets() {}

    static boolean secretName(String name) {
        return SECRET_NAME_PATTERN.matcher(name).matches();
    }

    static boolean literalSecretVariable(String name, String value) {
        if (value == null || value.isBlank() || !EnvVarResolver.findRefs(value).isEmpty()) return false;
        return secretName(name) || LITERAL_SECRET_PATTERN.matcher(value).matches();
    }

    static boolean literalSecretHeader(String name, String value) {
        if (value == null || value.isBlank() || !EnvVarResolver.findRefs(value).isEmpty()) return false;
        return SECRET_HEADER_PATTERN.matcher(name).matches() || LITERAL_SECRET_PATTERN.matcher(value).matches();
    }

}
