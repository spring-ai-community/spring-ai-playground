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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ImageReferenceHandler;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class McpToolCallingManagerImageTest {

    @Test
    void testToMediaMessageAttachesResolvedImageAsMedia() {
        byte[] bytes = {1, 2, 3, 4};
        ImageReferenceHandler.Resolved resolved = ImageReferenceHandler.Resolved.of(bytes, "image/png", "beach.png");
        UserMessage message = McpToolCallingManager.toMediaMessage(resolved);

        assertEquals(1, message.getMedia().size());
        assertArrayEquals(bytes, message.getMedia().get(0).getDataAsByteArray());
        assertEquals("image/png", message.getMedia().get(0).getMimeType().toString());
        assertTrue(message.getText().contains("beach.png"));
    }

    @Test
    void testToMediaMessageWithoutDescriptionStillAttaches() {
        ImageReferenceHandler.Resolved resolved = ImageReferenceHandler.Resolved.of(new byte[]{9}, "image/jpeg", null);
        UserMessage message = McpToolCallingManager.toMediaMessage(resolved);
        assertEquals(1, message.getMedia().size());
        assertEquals("image/jpeg", message.getMedia().get(0).getMimeType().toString());
    }

    @Test
    void testImageResultTextForResolvedMentionsAttachment() {
        ImageReferenceHandler.Resolved resolved = ImageReferenceHandler.Resolved.of(new byte[]{1}, "image/png", "x");
        assertTrue(McpToolCallingManager.imageResultText(resolved).contains("attached"));
    }

    @Test
    void testImageResultTextForMissingUsesNote() {
        ImageReferenceHandler.Resolved none = ImageReferenceHandler.Resolved.none("No matching image.");
        assertEquals("No matching image.", McpToolCallingManager.imageResultText(none));
    }

    @Test
    void testParseImageRequestExtractsRefAndQuestion() {
        ImageReferenceHandler.Request request = McpToolCallingManager.parseImageRequest(
                "{\"ref\":\"abc123\",\"question\":\"what is in this photo\"}");
        assertEquals("abc123", request.ref());
        assertEquals("what is in this photo", request.question());
    }

    @Test
    void testParseImageRequestHandlesBlankAndInvalid() {
        assertNull(McpToolCallingManager.parseImageRequest(null).ref());
        assertNull(McpToolCallingManager.parseImageRequest("").ref());
        assertNull(McpToolCallingManager.parseImageRequest("not-json{{{").ref());
    }

    @Test
    void testResolveImageReferenceCallsWithoutHandlerIsEmpty() {
        assertTrue(McpToolCallingManager.resolveImageReferenceCalls(mock(ChatResponse.class), null).isEmpty());
    }
}
