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
package org.springaicommunity.playground.service.mcp;

import java.util.Locale;

public final class ManagementToolNames {

    public static final String PREFIX = "saip_";

    private ManagementToolNames() {
    }

    public static boolean isReserved(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static String reservedMessage(String what, String name) {
        return what + " '" + name + "' starts with '" + PREFIX + "', which is reserved for this app's own "
                + "management tools";
    }

    public static void requireNotReserved(String what, String name) {
        if (isReserved(name)) {
            throw new IllegalArgumentException(reservedMessage(what, name));
        }
    }

}
