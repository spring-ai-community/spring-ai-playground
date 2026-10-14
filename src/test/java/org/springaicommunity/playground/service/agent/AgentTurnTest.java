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
package org.springaicommunity.playground.service.agent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.agent.AgentTurn.RoundVerdict;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnTest {

    private static AgentTurn turn(int soft, int hard, int identical, int interactions) {
        return new AgentTurn(new SpringAiPlaygroundOptions.AgentLoop(soft, hard, identical, interactions));
    }

    @Test
    void roundVerdictProgressesFromContinueToWrapUpToHardStop() {
        AgentTurn turn = turn(1, 2, 3, 1);
        assertEquals(RoundVerdict.CONTINUE, turn.beginRound());
        assertEquals(RoundVerdict.WRAP_UP, turn.beginRound());
        assertEquals(RoundVerdict.HARD_STOP, turn.beginRound());
        assertEquals(RoundVerdict.HARD_STOP, turn.verdict());
        assertEquals(3, turn.rounds());
    }

    @Test
    void cancelOverridesEveryOtherVerdict() {
        AgentTurn turn = turn(1, 2, 3, 1);
        turn.cancel();
        assertTrue(turn.cancelled());
        assertEquals(RoundVerdict.CANCELLED, turn.beginRound());
    }

    @Test
    void identicalCallsAreCountedPerFingerprint() {
        AgentTurn turn = turn(16, 18, 2, 1);
        assertFalse(turn.repeatedTooOften("readTextFile", "{\"path\":\"a\"}"));
        assertFalse(turn.repeatedTooOften("readTextFile", "{\"path\":\"a\"}"));
        assertTrue(turn.repeatedTooOften("readTextFile", "{\"path\":\"a\"}"));
        assertFalse(turn.repeatedTooOften("readTextFile", "{\"path\":\"b\"}"));
    }

    @Test
    void declinedToolsAreRememberedForTheTurn() {
        AgentTurn turn = turn(16, 18, 3, 1);
        assertFalse(turn.isDeclined("writeFile"));
        turn.markDeclined("writeFile");
        assertTrue(turn.isDeclined("writeFile"));
        assertFalse(turn.isDeclined("readTextFile"));
    }

    @Test
    void interactionBudgetResetsEachRound() {
        AgentTurn turn = turn(16, 18, 3, 1);
        turn.beginRound();
        assertTrue(turn.tryInteract());
        assertFalse(turn.tryInteract());
        turn.beginRound();
        assertTrue(turn.tryInteract());
    }

    @Test
    void fromFallsBackToDetachedWhenAbsent() {
        assertNotSame(AgentTurn.from(null), AgentTurn.from(Map.of()));
        AgentTurn detached = AgentTurn.from(Map.of());
        assertEquals(RoundVerdict.CONTINUE, detached.beginRound());
        assertTrue(detached.tryInteract());
        assertFalse(detached.repeatedTooOften("x", "{}"));
    }

    @Test
    void fromReturnsTheTurnPlacedInTheToolContext() {
        AgentTurn turn = turn(1, 2, 3, 1);
        assertSame(turn, AgentTurn.from(Map.of(AgentTurn.TOOL_CONTEXT_KEY, turn)));
    }

    @Test
    void policyDefaultsFillInNullAndNonPositiveValues() {
        SpringAiPlaygroundOptions.AgentLoop policy =
                new SpringAiPlaygroundOptions.AgentLoop(null, null, 0, -1);
        assertEquals(16, policy.softMaxRounds());
        assertEquals(18, policy.hardMaxRounds());
        assertEquals(3, policy.maxIdenticalCalls());
        assertEquals(1, policy.interactionsPerRound());
    }

    @Test
    void policyRaisesHardCapBelowSoftCapToTheSoftCap() {
        SpringAiPlaygroundOptions.AgentLoop policy =
                new SpringAiPlaygroundOptions.AgentLoop(20, 5, 3, 1);
        assertEquals(20, policy.softMaxRounds());
        assertEquals(20, policy.hardMaxRounds());
    }

    @Test
    void approvalWaitDefaultsToThreeMinutesAndClampsBelowTheStreamTimeout() {
        assertEquals(180, new SpringAiPlaygroundOptions.Hitl(null).approvalWaitSeconds());
        assertEquals(180, new SpringAiPlaygroundOptions.Hitl(0).approvalWaitSeconds());
        assertEquals(240, new SpringAiPlaygroundOptions.Hitl(9000).approvalWaitSeconds());
        assertEquals(Duration.ofSeconds(42), new SpringAiPlaygroundOptions.Hitl(42).approvalWait());
        assertEquals(Duration.ofSeconds(72), new SpringAiPlaygroundOptions.Hitl(42).transportTimeout());
    }
}
