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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.oauth.OAuthTokenEncryptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;

@Service
public class McpServerAuthTokenService {

    public static final String PROPERTY_NAME = "spring.ai.playground.mcp-server.auth-token";
    public static final String ENV_NAME = "SPRING_AI_PLAYGROUND_MCP_SERVER_AUTH_TOKEN";

    private static final Logger logger = LoggerFactory.getLogger(McpServerAuthTokenService.class);
    private static final String SECURITY_DIR_NAME = ".security";
    private static final String TOKEN_FILE_NAME = "mcp-server-token";
    private static final int GENERATED_TOKEN_BYTES = 32;

    public enum AccessMode { NONE, BEARER_TOKEN }

    public enum Source { NONE, CONFIGURATION, STORED, UNREADABLE }

    public record AuthTokenChangedEvent(AccessMode accessMode) {}

    private record State(Source source, String token) {}

    private final OAuthTokenEncryptor tokenEncryptor;
    private final ApplicationEventPublisher eventPublisher;
    private final Path tokenPath;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile State state;

    public McpServerAuthTokenService(SpringAiPlaygroundOptions playgroundOptions, Path springAiPlaygroundHomeDir,
            OAuthTokenEncryptor tokenEncryptor, ApplicationEventPublisher eventPublisher) {
        this.tokenEncryptor = tokenEncryptor;
        this.eventPublisher = eventPublisher;
        this.tokenPath = springAiPlaygroundHomeDir.resolve(SECURITY_DIR_NAME).resolve(TOKEN_FILE_NAME);
        this.state = playgroundOptions.mcpServer().authTokenRequired()
                ? new State(Source.CONFIGURATION, playgroundOptions.mcpServer().authToken()) : loadStored();
    }

    private State loadStored() {
        if (!Files.exists(this.tokenPath)) return new State(Source.NONE, null);
        try {
            String token = this.tokenEncryptor.decrypt(Files.readString(this.tokenPath, StandardCharsets.UTF_8).trim());
            if (!token.isBlank()) return new State(Source.STORED, token);
        } catch (IOException | RuntimeException e) {
            logger.error("Stored built-in MCP server token could not be read on this machine; /mcp stays locked "
                    + "until a new token is set: {}", e.getMessage());
        }
        return new State(Source.UNREADABLE, null);
    }

    public AccessMode accessMode() {
        return this.state.source() == Source.NONE ? AccessMode.NONE : AccessMode.BEARER_TOKEN;
    }

    public Source source() {
        return this.state.source();
    }

    public boolean editable() {
        return this.state.source() != Source.CONFIGURATION;
    }

    public Optional<String> currentToken() {
        return Optional.ofNullable(this.state.token());
    }

    public boolean matches(String presentedToken) {
        String expectedToken = this.state.token();
        return expectedToken != null && presentedToken != null
                && MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                        presentedToken.getBytes(StandardCharsets.UTF_8));
    }

    public String generate() {
        byte[] tokenBytes = new byte[GENERATED_TOKEN_BYTES];
        this.secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public synchronized void requireToken(String token) {
        assertEditable();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Bearer token must not be blank");
        String trimmedToken = token.strip();
        try {
            Files.createDirectories(this.tokenPath.getParent());
            Files.writeString(this.tokenPath, this.tokenEncryptor.encrypt(trimmedToken), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(this.tokenPath,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignore) {
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.state = new State(Source.STORED, trimmedToken);
        this.eventPublisher.publishEvent(new AuthTokenChangedEvent(AccessMode.BEARER_TOKEN));
    }

    public synchronized void open() {
        assertEditable();
        try {
            Files.deleteIfExists(this.tokenPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.state = new State(Source.NONE, null);
        this.eventPublisher.publishEvent(new AuthTokenChangedEvent(AccessMode.NONE));
    }

    private void assertEditable() {
        if (!editable())
            throw new IllegalStateException("Built-in MCP server token is set by configuration (" + PROPERTY_NAME + ")");
    }
}
