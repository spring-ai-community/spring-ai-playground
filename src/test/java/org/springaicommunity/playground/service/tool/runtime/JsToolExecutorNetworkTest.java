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
package org.springaicommunity.playground.service.tool.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.JsSandbox;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor.JsExecutionParams;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor.JsExecutionResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class JsToolExecutorNetworkTest {

    private static final Set<String> STANDARD_DENY =
            Set.of("java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
                    "java.lang.Class", "java.lang.invoke.*", "java.lang.reflect.*");
    private JsToolExecutor executor;

    @BeforeAll
    static void requiresOptIn() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_NETWORK_SMOKE")),
                "RUN_NETWORK_SMOKE=true not set; skipping live network test");
    }

    @BeforeEach
    void setUp() {
        executor = new JsToolExecutor(null, new JsSandbox(true, false, false, false, 50_000L,
                STANDARD_DENY,
                Set.of("java.lang.*", "java.math.*", "java.time.*", "java.util.*", "java.text.*", "java.net.*",
                        "java.io.*", "org.jsoup.*"),
                Map.of()), Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Test
    void testWeatherJsToolFunction() {

        String jsCode = """
                /**
                 * NOTE TO DEVELOPERS:
                 * This code runs on JavaScript (ECMAScript 2024) inside the JVM.
                 * It is NOT a browser or Node.js environment.
                 *
                 * Unavailable APIs:
                 * - Browser APIs: fetch, XMLHttpRequest, DOM (window/document), timers, etc.
                 * - Node.js APIs: require(), module, process, built-in modules, etc.
                 *
                 * Available features:
                 * - Java interop via Java.type() (e.g., java.net.*, java.io.*, etc.)
                 * - console.log (output captured by the host)
                 *
                 * Execution model:
                 * - Your script is wrapped in an async function.
                 * - The value you return becomes the final tool result.
                 */

                // Use Java standard HTTP classes from JavaScript
                var URL = Java.type('java.net.URL');
                var BufferedReader = Java.type('java.io.BufferedReader');
                var InputStreamReader = Java.type('java.io.InputStreamReader');

                // 1) Call wttr.in with JSON format (j1)
                var url = new URL('https://wttr.in/' + location + '?format=j1');
                var conn = url.openConnection();
                conn.setRequestMethod('GET');
                conn.setConnectTimeout(20000);
                var reader;

                try {
                    conn.setReadTimeout(20000);

                    // 2) Read the full JSON response as a string
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), 'UTF-8'));
                    var line;
                    var sb = '';
                    while ((line = reader.readLine()) !== null) {
                        sb += line;
                    }
                } finally {
                    if (reader != null) {
                        reader.close();
                    }
                }

                // 3) Parse JSON string into a JavaScript object
                var data = JSON.parse(sb);

                // 4) Extract only the fields we want:
                //    - location name
                //    - temperature (Celsius)
                //    - humidity
                //    - wind speed
                //    - wind direction
                var areaName = data.nearest_area
                                && data.nearest_area[0]
                                && data.nearest_area[0].areaName
                                && data.nearest_area[0].areaName[0]
                                && data.nearest_area[0].areaName[0].value;

                var current = data.current_condition && data.current_condition[0];

                var tempC    = current && current.temp_C;
                var humidity = current && current.humidity;
                var windKmph = current && current.windspeedKmph;
                var windDir  = current && current.winddir16Point; // e.g. N, NE, E, SE, ...

                // Build strings for wind speed & direction
                var windSpeedText = windKmph != null ? (windKmph + ' km/h') : null;
                var windDirText   = windDir != null ? windDir : null;

                // 5) Build a small summary object
                var summary = {
                    location: areaName || location,
                    tempC: tempC || null,
                    humidity: humidity || null,
                    windSpeed: windSpeedText,
                    windDirection: windDirText
                };

                // 6) Return a compact JSON string to Java
                return JSON.stringify(summary);
                """;

        JsExecutionParams params = new JsExecutionParams(Map.of("location", "seoul"), jsCode);

        JsExecutionResult result = executor.execute(params);

        System.out.println("isOk = " + result.isOk());
        System.out.println("result = " + result.result());
        System.out.println("error = " + result.error());

        assertTrue(result.isOk());
        assertNotNull(result.result());
    }
}
