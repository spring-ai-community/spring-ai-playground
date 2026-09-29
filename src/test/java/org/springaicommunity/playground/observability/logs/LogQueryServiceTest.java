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
package org.springaicommunity.playground.observability.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogFilter;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogLine;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogQueryResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LogQueryServiceTest {

    @Test
    void parsesFullLineWithUserSession() {
        String raw = "2026-05-31 12:34:56.789 [http-nio-8282-exec-1] INFO  "
                + "o.s.p.s.c.ChatService [user=abc123 sid=sess-1 conv=conv-9 msg=msg-7 "
                + "traceId=trace-5 spanId=span-3] - Calling tool";
        LogLine ln = LogQueryService.parse(raw);
        assertThat(ln.time()).isEqualTo("2026-05-31 12:34:56.789");
        assertThat(ln.level()).isEqualTo("INFO");
        assertThat(ln.logger()).isEqualTo("o.s.p.s.c.ChatService");
        assertThat(ln.userId()).isEqualTo("abc123");
        assertThat(ln.sessionId()).isEqualTo("sess-1");
        assertThat(ln.conv()).isEqualTo("conv-9");
        assertThat(ln.userMessageId()).isEqualTo("msg-7");
        assertThat(ln.traceId()).isEqualTo("trace-5");
        assertThat(ln.spanId()).isEqualTo("span-3");
        assertThat(ln.message()).isEqualTo("Calling tool");
    }

    @Test
    void parsesEmptyMdcValues() {
        String raw = "2026-05-31 12:34:56.789 [main] INFO  o.s.Foo "
                + "[user= sid= conv= msg= traceId= spanId=] - boot";
        LogLine ln = LogQueryService.parse(raw);
        assertThat(ln.userId()).isEmpty();
        assertThat(ln.sessionId()).isEmpty();
        assertThat(ln.message()).isEqualTo("boot");
    }

    @Test
    void nonConformingLineFallsBackToRaw() {
        String raw = "some random non-conforming line";
        LogLine ln = LogQueryService.parse(raw);
        assertThat(ln.level()).isEmpty();
        assertThat(ln.time()).isEmpty();
        assertThat(ln.message()).isEqualTo(raw);
        assertThat(ln.raw()).isEqualTo(raw);
    }

    @Test
    void blankLineReturnsNull() {
        assertThat(LogQueryService.parse("")).isNull();
        assertThat(LogQueryService.parse("   ")).isNull();
    }

    @TempDir
    Path home;

    private static String line(String time, String level, String sid, String rest) {
        return time + " [main] " + level + " o.s.p.Test [user=u1 sid=" + sid + " conv= msg= traceId= spanId=] - " + rest;
    }

    @Test
    void queryTailsTheLogFileAndAppliesEveryFilter() throws Exception {
        Path logDir = Files.createDirectories(home.resolve("logs"));
        Files.writeString(logDir.resolve("spring-ai-playground.log"), String.join("\n",
                line("2026-09-29 10:00:00.000", "INFO", "s1", "boot done"),
                line("2026-09-29 10:05:00.000", "ERROR", "s2", "Tool failed: boom"),
                "  at some.stack.Frame(Frame.java:1)",
                line("2026-09-29 10:10:00.000", "WARN", "s1", "Retrying tool"),
                line("2026-09-29 10:20:00.000", "INFO", "s2", "all good")));
        LogQueryService service = new LogQueryService(home);
        LogFilter open = new LogFilter("ALL", "ALL", "", true, false, null, null);

        LogQueryResult all = service.query(open);
        assertThat(all.status()).isEqualTo(LogQueryService.Status.OK);
        assertThat(all.all()).hasSize(5);
        assertThat(all.matched()).as("the stack frame has no timestamp and is dropped").hasSize(4);
        assertThat(all.tailChars()).isGreaterThan(100);

        assertThat(service.query(new LogFilter("ERROR", null, "", true, false, null, null)).matched())
                .extracting(LogLine::message).containsExactly("Tool failed: boom");
        assertThat(service.query(new LogFilter("ALL", "s1", "TOOL", true, false, null, null)).matched())
                .extracting(LogLine::message).containsExactly("Retrying tool");
        assertThat(service.query(new LogFilter("ALL", "s1", "TOOL", false, false, null, null)).matched()).isEmpty();
        LogQueryResult regex = service.query(new LogFilter("ALL", "ALL", "boom|good", true, true, null, null));
        assertThat(regex.regexUsed()).isTrue();
        assertThat(regex.matched()).extracting(LogLine::level).containsExactly("ERROR", "INFO");
        LogQueryResult badRegex = service.query(new LogFilter("ALL", "ALL", "boom(", true, true, null, null));
        assertThat(badRegex.regexUsed()).isFalse();
        assertThat(badRegex.regexError()).isNotBlank();
        assertThat(badRegex.matched()).as("falls back to a literal search").isEmpty();
        assertThat(service.query(new LogFilter("ALL", "ALL", "", true, false,
                LocalDateTime.of(2026, 9, 29, 10, 4), LocalDateTime.of(2026, 9, 29, 10, 15))).matched())
                .extracting(LogLine::time).containsExactly("2026-09-29 10:05:00.000", "2026-09-29 10:10:00.000");
        assertThat(new LogFilter("ALL", "ALL", " ", true, false, null, null).hasFilter()).isFalse();
        assertThat(new LogFilter("WARN", "ALL", "", true, false, null, null).hasFilter()).isTrue();
    }

    @Test
    void queryReportsAMissingLogFile() {
        LogQueryService service = new LogQueryService(home);
        LogQueryResult result = service.query(new LogFilter("ALL", "ALL", "", true, false, null, null));
        assertThat(result.status()).isEqualTo(LogQueryService.Status.FILE_MISSING);
        assertThat(service.logFile()).isEqualTo(home.resolve("logs").resolve("spring-ai-playground.log"));
    }
}
