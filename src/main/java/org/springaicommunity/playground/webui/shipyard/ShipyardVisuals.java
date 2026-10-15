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
package org.springaicommunity.playground.webui.shipyard;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.ShippedTool;

import java.util.Locale;

interface ShipyardVisuals {

    String[] AVATAR_COLORS = { "#1676f3", "#8b5cf6", "#0ea5e9", "#e05252", "#2c7a44", "#b45309" };

    enum AvatarSize {

        SIDEBAR("32px", "var(--lumo-border-radius-m)", "var(--lumo-font-size-xs)"),
        PREVIEW("44px", "var(--lumo-border-radius-m)", "var(--lumo-font-size-s)"),
        HERO("56px", "var(--lumo-border-radius-l)", "var(--lumo-font-size-l)");

        private final String box;
        private final String cornerRadius;
        private final String fontSize;

        AvatarSize(String box, String cornerRadius, String fontSize) {
            this.box = box;
            this.cornerRadius = cornerRadius;
            this.fontSize = fontSize;
        }
    }

    static Div serverAvatar(String serverName, AvatarSize size) {
        Div avatar = new Div(new Span(initials(serverName)));
        avatar.getStyle().set("width", size.box).set("height", size.box)
                .set("border-radius", size.cornerRadius).set("font-size", size.fontSize)
                .set("background", avatarColor(serverName)).set("color", "#fff")
                .set("display", "flex").set("align-items", "center").set("justify-content", "center")
                .set("font-weight", "700").set("flex", "none");
        return avatar;
    }

    static String initials(String serverName) {
        StringBuilder initials = new StringBuilder();
        for (String part : serverName.split("[-_. ]+")) {
            if (!part.isBlank() && initials.length() < 2) {
                initials.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return initials.isEmpty() ? "?" : initials.toString();
    }

    static String avatarColor(String serverName) {
        return AVATAR_COLORS[Math.floorMod(serverName.toLowerCase(Locale.ROOT).hashCode(),
                AVATAR_COLORS.length)];
    }

    static Span badge(String text) {
        Span badge = new Span(text);
        badge.getElement().getThemeList().add("badge");
        return badge;
    }

    static String toolCount(int count) {
        return count == 1 ? "1 tool" : count + " tools";
    }

    static Span kindBadge(String kind) {
        ShippedTool.Kind parsed = parseKind(kind);
        Span badge = badge(parsed == null ? "-" : parsed.label());
        String background = parsed == null ? "var(--lumo-contrast-5pct)" : switch (parsed) {
            case TOOL, BUILT_IN -> "var(--lumo-contrast-10pct)";
            case PROXIED -> "rgba(124, 77, 223, 0.12)";
            case KNOWLEDGE -> "rgba(0, 137, 123, 0.12)";
        };
        String color = parsed == ShippedTool.Kind.PROXIED ? "#6a3fc8"
                : parsed == ShippedTool.Kind.KNOWLEDGE ? "#00695c" : "var(--lumo-body-text-color)";
        badge.getStyle().set("background", background).set("color", color);
        return badge;
    }

    static ShippedTool.Kind parseKind(String kind) {
        if (kind == null) return null;
        try {
            return ShippedTool.Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String modelBadge(BundleManifest.Models models) {
        if (models.modelFree()) {
            return "model-free";
        }
        StringBuilder label = new StringBuilder();
        if (models.chat() != null) {
            label.append("chat: ").append(models.chat().model());
        }
        if (models.embedding() != null) {
            if (!label.isEmpty()) {
                label.append(", ");
            }
            label.append("embedding: ").append(models.embedding().model());
        }
        return label.isEmpty() ? "model choice pending" : label.toString();
    }

    static Span statusChip(DeployTarget target, DockerAvailability docker, ContainerStatus status) {
        String text;
        String background;
        String where = target.isLocal() ? ":" + status.hostPort() : target.name() + ":" + status.hostPort();
        if (!docker.cliFound()) {
            text = "Docker CLI not found";
            background = "var(--lumo-warning-color-10pct)";
        } else if (!docker.daemonUp()) {
            text = target.isLocal() ? "Docker not running" : "Target " + target.name() + " unreachable";
            background = "var(--lumo-warning-color-10pct)";
        } else if (status.state() == ContainerState.RUNNING && !status.unavailable().isEmpty()) {
            text = (status.hostPort() == null ? "Running" : "Running on " + where) + " · "
                    + status.unavailable().size() + " unavailable";
            background = "var(--lumo-warning-color-10pct)";
        } else if (status.state() == ContainerState.RUNNING) {
            text = status.hostPort() == null ? "Running" : "Running on " + where;
            background = "var(--lumo-success-color-10pct)";
        } else if (status.state() == ContainerState.STARTING) {
            text = "Starting on " + where;
            background = "var(--lumo-warning-color-10pct)";
        } else if (status.state() == ContainerState.STOPPED) {
            text = "Stopped";
            background = "var(--lumo-contrast-10pct)";
        } else {
            text = "Not deployed";
            background = "var(--lumo-contrast-5pct)";
        }
        Span chip = badge(text);
        chip.getStyle().set("background", background);
        return chip;
    }

}
