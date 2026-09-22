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
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AuthTokenChangedEvent;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.Source;
import org.springaicommunity.playground.service.oauth.OAuthTokenEncryptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerAuthTokenServiceTest {

    @TempDir
    Path homeDir;

    private final List<Object> publishedEvents = new ArrayList<>();

    private McpServerAuthTokenService newService(String configuredToken) throws IOException {
        ApplicationEventPublisher eventPublisher = this.publishedEvents::add;
        return new McpServerAuthTokenService(new SpringAiPlaygroundOptions(null, false, null, null, null,
                new SpringAiPlaygroundOptions.McpServer(null, null, configuredToken)), this.homeDir,
                new OAuthTokenEncryptor(this.homeDir), eventPublisher);
    }

    private Path tokenPath() {
        return this.homeDir.resolve(".security").resolve("mcp-server-token");
    }

    @Test
    void startsOpenWithNoPropertyAndNoStoredToken() throws IOException {
        McpServerAuthTokenService service = newService(null);

        assertThat(service.accessMode()).isEqualTo(AccessMode.NONE);
        assertThat(service.source()).isEqualTo(Source.NONE);
        assertThat(service.editable()).isTrue();
        assertThat(service.matches("anything")).isFalse();
    }

    @Test
    void requiredTokenIsStoredEncryptedAndSurvivesARestart() throws IOException {
        McpServerAuthTokenService service = newService(null);

        service.requireToken("  stored-secret-token  ");

        assertThat(service.accessMode()).isEqualTo(AccessMode.BEARER_TOKEN);
        assertThat(service.source()).isEqualTo(Source.STORED);
        assertThat(service.matches("stored-secret-token")).isTrue();
        assertThat(service.matches("Stored-secret-token")).isFalse();
        assertThat(Files.readString(tokenPath())).doesNotContain("stored-secret-token");
        assertThat(this.publishedEvents).containsExactly(new AuthTokenChangedEvent(AccessMode.BEARER_TOKEN));

        McpServerAuthTokenService restarted = newService(null);
        assertThat(restarted.source()).isEqualTo(Source.STORED);
        assertThat(restarted.currentToken()).contains("stored-secret-token");
    }

    @Test
    void openDeletesTheStoredToken() throws IOException {
        McpServerAuthTokenService service = newService(null);
        service.requireToken("stored-secret-token");

        service.open();

        assertThat(service.accessMode()).isEqualTo(AccessMode.NONE);
        assertThat(tokenPath()).doesNotExist();
        assertThat(newService(null).source()).isEqualTo(Source.NONE);
        assertThat(this.publishedEvents).last().isEqualTo(new AuthTokenChangedEvent(AccessMode.NONE));
    }

    @Test
    void configuredTokenWinsOverTheStoredOneAndIsNotEditable() throws IOException {
        newService(null).requireToken("stored-secret-token");

        McpServerAuthTokenService service = newService("configured-token");

        assertThat(service.source()).isEqualTo(Source.CONFIGURATION);
        assertThat(service.editable()).isFalse();
        assertThat(service.matches("configured-token")).isTrue();
        assertThat(service.matches("stored-secret-token")).isFalse();
        assertThatThrownBy(() -> service.requireToken("another-token")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::open).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unreadableStoredTokenKeepsTheServerLockedUntilANewOneIsSet() throws IOException {
        Files.createDirectories(tokenPath().getParent());
        Files.writeString(tokenPath(), "not-a-ciphertext");

        McpServerAuthTokenService service = newService(null);

        assertThat(service.source()).isEqualTo(Source.UNREADABLE);
        assertThat(service.accessMode()).isEqualTo(AccessMode.BEARER_TOKEN);
        assertThat(service.currentToken()).isEmpty();
        assertThat(service.matches("not-a-ciphertext")).isFalse();

        service.requireToken("replacement-token");
        assertThat(service.source()).isEqualTo(Source.STORED);
        assertThat(service.matches("replacement-token")).isTrue();
    }

    @Test
    void blankTokenIsRejectedAndGeneratedTokensAreLongAndDistinct() throws IOException {
        McpServerAuthTokenService service = newService(null);

        assertThatThrownBy(() -> service.requireToken("  ")).isInstanceOf(IllegalArgumentException.class);
        String firstToken = service.generate();
        assertThat(firstToken).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=");
        assertThat(service.generate()).isNotEqualTo(firstToken);
    }
}
