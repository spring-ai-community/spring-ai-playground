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

public record TraceEvent(String stage, Level level, String message, Object payload, long timestampMs) {

    public enum Level { INFO, WARN, ERROR }

    public static TraceEvent info(String stage, String message, Object payload) {
        return new TraceEvent(stage, Level.INFO, message, payload, System.currentTimeMillis());
    }

    public static TraceEvent info(String stage, String message) {
        return info(stage, message, null);
    }

    public static TraceEvent warn(String stage, String message) {
        return new TraceEvent(stage, Level.WARN, message, null, System.currentTimeMillis());
    }

    public static TraceEvent error(String stage, String message) {
        return new TraceEvent(stage, Level.ERROR, message, null, System.currentTimeMillis());
    }
}
