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
package org.springaicommunity.playground.service.mcp.risk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springaicommunity.playground.service.mcp.catalog.McpToolDescriptor;

import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolHashLedgerTest {

    @TempDir
    Path tempHome;

    private McpToolHashLedger ledger;
    private PersistenceExecutor executor;
    private AtomicReference<McpRiskEvents.HashLedgerMismatch> captured;
    private AtomicReference<McpRiskEvents.HashLedgerRecanonicalized> rebaselined;
    private ObjectMapper objectMapper;
    private McpRiskSignalSink sink;

    @BeforeEach
    void setUp() throws IOException {
        this.objectMapper = new ObjectMapper();
        this.executor = new PersistenceExecutor();
        this.captured = new AtomicReference<>();
        this.rebaselined = new AtomicReference<>();
        this.sink = new McpRiskSignalSink() {
            @Override public void onServerRiskComputed(McpRiskEvents.ServerRiskComputed event) {}
            @Override public void onToolPublishRiskComputed(McpRiskEvents.ToolPublishRiskComputed event) {}
            @Override public void onFloorOverrideTriggered(McpRiskEvents.FloorOverrideTriggered event) {}
            @Override public void onHashLedgerMismatch(McpRiskEvents.HashLedgerMismatch event) {
                captured.set(event);
            }
            @Override public void onHashLedgerRecanonicalized(McpRiskEvents.HashLedgerRecanonicalized event) {
                rebaselined.set(event);
            }
            @Override public void onCompositionLifecycle(McpRiskEvents.CompositionLifecycle event) {}
            @Override public void onPoisoningHit(McpRiskEvents.PoisoningHit event) {}
        };
        this.ledger = new McpToolHashLedger(tempHome, objectMapper, new CanonicalHasher(objectMapper), executor, sink);
    }

    @AfterEach
    void drainPersistence() throws InterruptedException, TimeoutException {
        executor.awaitCompletion(Duration.ofSeconds(5));
    }

    @Test
    void corruptLedgerFileStartsEmptyInsteadOfCrashing() throws IOException {
        Path ledgerFile = tempHome.resolve("mcp").resolve("risk").resolve("ledger.json");
        Files.createDirectories(ledgerFile.getParent());
        Files.writeString(ledgerFile, "{ not a fingerprint array ]]]");
        McpToolHashLedger recovered = assertDoesNotThrow(
                () -> new McpToolHashLedger(tempHome, objectMapper, new CanonicalHasher(objectMapper), executor, sink));
        assertTrue(recovered.get("github", "list_repos").isEmpty());
    }

    private static CanonicalHasher.ContentDigest digest(String value) {
        return new CanonicalHasher.ContentDigest(CanonicalHasher.MCP_TOOL_SCHEME, value);
    }

    @Test
    void contentDigestIsStableForSameInputs() {
        McpToolDescriptor.Annotations annotations = new McpToolDescriptor.Annotations("List Repos", true, false, true, true);
        CanonicalHasher.ContentDigest first = ledger.computeDigest("list_repos", "Lists repositories", null, annotations);
        CanonicalHasher.ContentDigest second = ledger.computeDigest("list_repos", "Lists repositories", null, annotations);
        assertEquals(first, second);
        assertEquals(64, first.value().length(), "SHA-256 hex length");
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME, first.scheme());
    }

    @Test
    void contentDigestChangesWhenDescriptionChanges() {
        McpToolDescriptor.Annotations annotations = new McpToolDescriptor.Annotations(null, true, false, true, true);
        CanonicalHasher.ContentDigest first = ledger.computeDigest("tool", "version 1 description", null, annotations);
        CanonicalHasher.ContentDigest second = ledger.computeDigest("tool", "version 2 description", null, annotations);
        assertNotEquals(first.value(), second.value());
    }

    @Test
    void contentDigestIgnoresInputSchemaKeyOrder() {
        JsonNode a = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"},\"y\":{\"type\":\"number\"}}}");
        JsonNode b = objectMapper.readTree("{\"properties\":{\"y\":{\"type\":\"number\"},\"x\":{\"type\":\"string\"}},\"type\":\"object\"}");
        McpToolDescriptor.Annotations ann = new McpToolDescriptor.Annotations(null, true, false, true, true);
        assertEquals(ledger.computeDigest("t", "d", a, ann), ledger.computeDigest("t", "d", b, ann));
    }

    @Test
    void contentDigestChangesWhenSchemaContentChanges() {
        JsonNode a = objectMapper.readTree("{\"properties\":{\"x\":{\"type\":\"string\"}}}");
        JsonNode b = objectMapper.readTree("{\"properties\":{\"x\":{\"type\":\"number\"}}}");
        McpToolDescriptor.Annotations ann = new McpToolDescriptor.Annotations(null, true, false, true, true);
        assertNotEquals(ledger.computeDigest("t", "d", a, ann).value(), ledger.computeDigest("t", "d", b, ann).value());
    }

    @Test
    void digestCannotBeConstructedWithoutADeclaredScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalHasher.ContentDigest(null, "hash-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalHasher.ContentDigest("  ", "hash-1"));
    }

    @Test
    void firstSeenIsRecordedAsNew() {
        McpToolHashLedger.CheckResult result = ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        assertEquals(McpToolHashLedger.CheckResult.Status.NEW, result.status());
        assertNull(result.stored());
        assertNotNull(result.current());
        assertEquals("hash-1", result.current().contentHash());
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME, result.current().canonicalization());
        assertEquals(McpToolHashLedger.Fingerprint.LifecycleStatus.ACTIVE, result.current().status());
    }

    @Test
    void sameHashAfterFirstSeenReturnsUnchanged() {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        McpToolHashLedger.CheckResult result = ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        assertEquals(McpToolHashLedger.CheckResult.Status.UNCHANGED, result.status());
        assertNull(captured.get(), "no mismatch event emitted for unchanged");
    }

    @Test
    void hashChangeMarksAwaitingRereviewAndEmits() {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        McpToolHashLedger.CheckResult result = ledger.checkAndRecord("github", "list_repos", digest("hash-2"));
        assertEquals(McpToolHashLedger.CheckResult.Status.MISMATCH, result.status());
        assertTrue(result.isBlocking());
        assertEquals(McpToolHashLedger.Fingerprint.LifecycleStatus.AWAITING_REREVIEW, result.current().status());
        McpRiskEvents.HashLedgerMismatch event = captured.get();
        assertNotNull(event, "mismatch event must be emitted");
        assertEquals("hash-1", event.previousHash());
        assertEquals("hash-2", event.currentHash());
    }

    @Test
    void schemeChangeRebaselinesInsteadOfReportingTamper() {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        McpToolHashLedger.CheckResult result = ledger.checkAndRecord("github", "list_repos",
                new CanonicalHasher.ContentDigest("jcs-rfc8785-sha256/v1", "hash-under-other-scheme"));

        assertEquals(McpToolHashLedger.CheckResult.Status.RECANONICALIZED, result.status());
        assertFalse(result.isBlocking(), "a recipe change is not evidence that the tool definition changed");
        assertEquals(McpToolHashLedger.Fingerprint.LifecycleStatus.ACTIVE, result.current().status());
        assertEquals("jcs-rfc8785-sha256/v1", result.current().canonicalization());
        assertEquals("hash-under-other-scheme", result.current().contentHash());
        assertNull(captured.get(), "re-canonicalization must not emit a tamper signal");
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME, rebaselined.get().previousScheme());
        assertEquals("jcs-rfc8785-sha256/v1", rebaselined.get().currentScheme());
        assertEquals("hash-1", rebaselined.get().previousHash());
        assertEquals("hash-under-other-scheme", rebaselined.get().currentHash());
    }

    @Test
    void schemeChangePreservesAwaitingRereviewInsteadOfClearingIt() {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        ledger.checkAndRecord("github", "list_repos", digest("hash-2"));
        McpToolHashLedger.CheckResult result = ledger.checkAndRecord("github", "list_repos",
                new CanonicalHasher.ContentDigest("jcs-rfc8785-sha256/v1", "hash-under-other-scheme"));

        assertEquals(McpToolHashLedger.CheckResult.Status.RECANONICALIZED, result.status());
        assertEquals(McpToolHashLedger.Fingerprint.LifecycleStatus.AWAITING_REREVIEW, result.current().status(),
                "re-canonicalizing must not launder a pending re-review into an approved tool");
    }

    @Test
    void legacyFingerprintWithoutSchemeIsStampedOnNextUnchangedCheck() throws Exception {
        Path ledgerFile = tempHome.resolve("mcp").resolve("risk").resolve("ledger.json");
        Files.createDirectories(ledgerFile.getParent());
        Files.writeString(ledgerFile, "[{\"serverId\":\"github\",\"toolName\":\"list_repos\","
                + "\"contentHash\":\"hash-1\",\"firstSeenAtEpochMs\":1,\"status\":\"ACTIVE\"}]");
        McpToolHashLedger reopened = new McpToolHashLedger(tempHome, objectMapper,
                new CanonicalHasher(objectMapper), executor, sink);
        assertNull(reopened.get("github", "list_repos").orElseThrow().canonicalization());

        McpToolHashLedger.CheckResult result = reopened.checkAndRecord("github", "list_repos", digest("hash-1"));

        assertEquals(McpToolHashLedger.CheckResult.Status.UNCHANGED, result.status());
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME, result.current().canonicalization());
        assertNull(captured.get(), "grandfathering an undeclared fingerprint is not a tamper signal");
        assertNull(rebaselined.get(), "grandfathering declares a scheme, it does not switch one");
    }

    @Test
    void approveRereviewRestoresActiveStatus() {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        ledger.checkAndRecord("github", "list_repos", digest("hash-2"));
        ledger.approveRereview("github", "list_repos", digest("hash-2"));
        McpToolHashLedger.Fingerprint fp = ledger.get("github", "list_repos").orElseThrow();
        assertEquals(McpToolHashLedger.Fingerprint.LifecycleStatus.ACTIVE, fp.status());
        assertEquals("hash-2", fp.contentHash());
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME, fp.canonicalization());
    }

    @Test
    void persistsToDiskAndReloadsOnInit() throws Exception {
        ledger.checkAndRecord("github", "list_repos", digest("hash-1"));
        ledger.checkAndRecord("notion", "read_page", digest("hash-2"));
        executor.awaitCompletion(Duration.ofSeconds(5));

        McpToolHashLedger reopened = new McpToolHashLedger(tempHome, objectMapper,
                new CanonicalHasher(objectMapper), executor, McpRiskSignalSink.NOOP);
        assertEquals(2, reopened.snapshot().size());
        assertEquals("hash-1", reopened.get("github", "list_repos").orElseThrow().contentHash());
        assertEquals("hash-2", reopened.get("notion", "read_page").orElseThrow().contentHash());
        assertEquals(CanonicalHasher.MCP_TOOL_SCHEME,
                reopened.get("github", "list_repos").orElseThrow().canonicalization());
    }
}
