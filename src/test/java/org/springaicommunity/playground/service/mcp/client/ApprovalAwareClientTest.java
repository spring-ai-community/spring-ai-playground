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
package org.springaicommunity.playground.service.mcp.client;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ApprovalAwareClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApprovalAwareClientTest {

    private final McpClientOps ops = mock(McpClientOps.class);
    private final ApprovalAwareClient client = new ApprovalAwareClient(ops);

    @Test
    void retiringWhileACallIsInFlightClosesOnlyAfterTheCallReleases() {
        assertTrue(client.acquire());

        client.retire();

        verify(ops, never()).close();
        client.release();
        verify(ops).close();
    }

    @Test
    void retiringAnIdleClientClosesItAtOnce() {
        client.retire();

        verify(ops).close();
        assertFalse(client.acquire());
    }

    @Test
    void releasingWithoutRetirementKeepsTheClientOpen() {
        assertTrue(client.acquire());
        client.release();

        verify(ops, never()).close();
        assertTrue(client.acquire());
    }

}
