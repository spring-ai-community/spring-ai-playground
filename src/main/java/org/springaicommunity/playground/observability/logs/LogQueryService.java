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

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class LogQueryService {

    public static final int MAX_TAIL_BYTES = 4 * 1024 * 1024;
    public static final String NO_FILTER_VALUE = "ALL";

    public record LogLine(String time, String level, String logger, String userId, String sessionId, String conv,
            String userMessageId, String traceId, String spanId, String message, String raw) {}

    public record LogFilter(String level, String sessionId, String text, boolean caseInsensitive, boolean regex,
            LocalDateTime from, LocalDateTime to) {

        public String trimmedText() {
            return this.text == null ? "" : this.text.trim();
        }

        public boolean hasFilter() {
            return !trimmedText().isEmpty() || isSpecificFilterValue(this.level) || isSpecificFilterValue(this.sessionId);
        }
    }

    public enum Status { OK, FILE_MISSING, READ_FAILED }

    public record LogQueryResult(Status status, List<LogLine> all, List<LogLine> matched, int tailChars,
            boolean regexUsed, String regexError) {}

    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
            "^(?<time>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
                    + "\\[(?<thread>[^\\]]+)\\]\\s+"
                    + "(?<level>[A-Z]+)\\s+"
                    + "(?<logger>\\S+)\\s+"
                    + "\\[user=(?<user>[^ ]*) sid=(?<sid>[^ ]*) conv=(?<conv>[^ ]*) msg=(?<msg>[^ ]*) "
                    + "traceId=(?<traceId>[^ ]*) spanId=(?<spanId>[^\\]]*)\\]\\s+-\\s+"
                    + "(?<rest>.*)$");

    private final Path logFile;

    public LogQueryService(Path springAiPlaygroundHomeDir) {
        this.logFile = springAiPlaygroundHomeDir.resolve("logs").resolve("spring-ai-playground.log");
    }

    public Path logFile() {
        return this.logFile;
    }

    public LogQueryResult query(LogFilter filter) {
        if (!Files.isRegularFile(this.logFile))
            return new LogQueryResult(Status.FILE_MISSING, List.of(), List.of(), 0, false, null);
        String tail = readTail();
        if (tail == null) return new LogQueryResult(Status.READ_FAILED, List.of(), List.of(), 0, false, null);
        List<LogLine> all = parseAll(tail);

        String contains = filter.trimmedText();
        boolean useRegex = filter.regex() && !contains.isEmpty();
        Pattern pattern = null;
        String regexError = null;
        if (useRegex) {
            try {
                pattern = filter.caseInsensitive() ? Pattern.compile(contains, Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(contains);
            } catch (PatternSyntaxException ex) {
                regexError = ex.getDescription();
                useRegex = false;
            }
        }
        String containsLc = contains.toLowerCase();
        List<LogLine> matched = new ArrayList<>(all.size());
        for (LogLine line : all) {
            if (isSpecificFilterValue(filter.level()) && !filter.level().equals(line.level())) continue;
            if (isSpecificFilterValue(filter.sessionId()) && !filter.sessionId().equals(line.sessionId())) continue;
            LocalDateTime time = parseLogTime(line.time());
            if (time == null) continue;
            if (filter.from() != null && time.isBefore(filter.from())) continue;
            if (filter.to() != null && time.isAfter(filter.to())) continue;
            if (!contains.isEmpty()) {
                if (useRegex) {
                    if (!pattern.matcher(line.raw()).find()) continue;
                } else {
                    String hay = filter.caseInsensitive() ? line.raw().toLowerCase() : line.raw();
                    if (!hay.contains(filter.caseInsensitive() ? containsLc : contains)) continue;
                }
            }
            matched.add(line);
        }
        matched.sort(Comparator.comparing(LogLine::time, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new LogQueryResult(Status.OK, all, matched, tail.length(), useRegex, regexError);
    }

    public static List<LogLine> parseAll(String tail) {
        String[] rawLines = tail.split("\\R");
        List<LogLine> all = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            LogLine line = parse(raw);
            if (line != null) all.add(line);
        }
        return all;
    }

    public static LogLine parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher matcher = LOG_LINE_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            return new LogLine("", "", "", "", "", "", "", "", "", raw, raw);
        }
        return new LogLine(matcher.group("time"), matcher.group("level"), matcher.group("logger"),
                matcher.group("user"), matcher.group("sid"), matcher.group("conv"), matcher.group("msg"),
                matcher.group("traceId"), matcher.group("spanId"), matcher.group("rest"), raw);
    }

    public static LocalDateTime parseLogTime(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return LocalDateTime.parse(text, LOG_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static boolean isSpecificFilterValue(String value) {
        return value != null && !value.isBlank() && !NO_FILTER_VALUE.equals(value);
    }

    private String readTail() {
        try (RandomAccessFile raf = new RandomAccessFile(this.logFile.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0, len - MAX_TAIL_BYTES);
            raf.seek(start);
            byte[] buf = new byte[(int) Math.min(MAX_TAIL_BYTES, len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
