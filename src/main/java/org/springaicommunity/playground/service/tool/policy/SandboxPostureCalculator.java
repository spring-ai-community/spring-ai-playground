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
package org.springaicommunity.playground.service.tool.policy;

import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Component
public class SandboxPostureCalculator {

    public record Inputs(String networkMode, Set<String> allowedHosts,
                         boolean fileRead, boolean fileWrite, boolean destructive,
                         Set<String> baselineDeny, Set<String> userDeny,
                         Set<String> baselineAllow, Set<String> userAllow) {}

    public record Reason(String setting, RiskLevel raisesTo, String why, String suggestion) {}

    public record Explanation(RiskLevel level, List<Reason> reasons, boolean elevated) {

        public Explanation {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public List<String> suggestions() {
            return this.reasons.stream().map(Reason::suggestion).filter(Objects::nonNull).distinct().toList();
        }
    }

    public RiskLevel compute(Inputs inputs) {
        return explain(inputs).level();
    }

    public Explanation explain(Inputs inputs) {
        List<Reason> reasons = new ArrayList<>();
        boolean classesChanged = false;

        if ("allowlist".equals(inputs.networkMode())) {
            Set<String> hosts = inputs.allowedHosts();
            if (hosts != null && hosts.contains("*")) {
                reasons.add(new Reason("networkMode=allowlist hostsAllow=*", RiskLevel.L4,
                        "The wildcard host allows any destination.",
                        "List the exact hosts in hostsAllow instead of '*' to stay at L3."));
            } else if (hosts != null && !hosts.isEmpty()) {
                reasons.add(new Reason("networkMode=allowlist", RiskLevel.L3,
                        "The tool can reach the listed hosts: " + new TreeSet<>(hosts) + ".", null));
            }
        }
        if ("strict".equals(inputs.networkMode())) {
            reasons.add(new Reason("networkMode=strict", RiskLevel.L3,
                    "The tool can reach any public host under the strict egress policy.",
                    "Use networkMode=allowlist with the hosts in hostsAllow when they are known in advance (also L3)."));
        }
        if ("open".equals(inputs.networkMode())) {
            reasons.add(new Reason("networkMode=open", RiskLevel.L4, "Any host can be reached.",
                    "Use networkMode=allowlist with hostsAllow to stay at L3."));
        }

        if (inputs.fileWrite()) {
            reasons.add(new Reason("fileWrite=true", RiskLevel.L4, "The tool can write files inside its workspace.",
                    "Drop fileWrite and keep only fileRead if the tool does not need to write (L3)."));
        } else if (inputs.fileRead()) {
            reasons.add(new Reason("fileRead=true", RiskLevel.L3, "The tool can read files.", null));
        }
        if (inputs.destructive()) {
            reasons.add(new Reason("destructive=true", RiskLevel.L5,
                    "The tool is declared to change or delete things outside itself.",
                    "Leave destructive off unless the tool must delete or overwrite data."));
        }

        Set<String> removedDeny = new TreeSet<>(inputs.baselineDeny() == null ? Set.of() : inputs.baselineDeny());
        if (inputs.userDeny() != null) removedDeny.removeAll(inputs.userDeny());
        if (!removedDeny.isEmpty()) {
            classesChanged = true;
            RiskLevel raisesTo = removedDeny.stream().anyMatch(SandboxPostureCalculator::isCriticalClass)
                    ? RiskLevel.L5 : removedDeny.size() >= 3 ? RiskLevel.L4 : RiskLevel.L3;
            reasons.add(new Reason("removeDenyClasses=" + removedDeny, raisesTo,
                    "Classes the sandbox denies by default are allowed for this tool.",
                    "Do not remove deny entries. Use the helpers of the tool runtime (fetch, safety.fs) instead."));
        }

        Set<String> addedAllow = new TreeSet<>(inputs.userAllow() == null ? Set.of() : inputs.userAllow());
        addedAllow.removeAll(inputs.baselineAllow() == null ? Set.of() : inputs.baselineAllow());
        if (!addedAllow.isEmpty()) {
            classesChanged = true;
            RiskLevel raisesTo;
            if (addedAllow.stream().anyMatch(SandboxPostureCalculator::isCriticalClass)
                    || addedAllow.stream().anyMatch(SandboxPostureCalculator::isFileWriteClass)) {
                raisesTo = RiskLevel.L5;
            } else if (addedAllow.stream().anyMatch(SandboxPostureCalculator::isReflectionClass)
                    || addedAllow.stream().anyMatch(SandboxPostureCalculator::isNetworkClass)
                    || addedAllow.stream().anyMatch(SandboxPostureCalculator::isFileReadClass)) {
                raisesTo = RiskLevel.L4;
            } else {
                raisesTo = RiskLevel.L3;
            }
            reasons.add(new Reason("addAllowClasses=" + addedAllow, raisesTo,
                    "Java classes beyond the sandbox baseline are made available to the code.",
                    "Avoid extra classes; the JavaScript runtime and its helpers cover most needs at a lower level."));
        }

        RiskLevel level = reasons.stream().map(Reason::raisesTo).reduce(RiskLevel.L0, SandboxPostureCalculator::max);
        boolean elevated = classesChanged || level.ordinal() >= RiskLevel.L4.ordinal();
        return new Explanation(level, reasons, elevated);
    }

    static boolean isCriticalClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.lang.System")
                || name.startsWith("java.lang.Runtime")
                || name.startsWith("java.lang.Process")
                || name.startsWith("java.lang.ProcessBuilder");
    }

    static boolean isReflectionClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.lang.reflect")
                || name.startsWith("java.lang.invoke")
                || name.equals("java.lang.Class");
    }

    static boolean isNetworkClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.net.http")
                || name.startsWith("java.net.Socket")
                || name.startsWith("java.net.URL")
                || name.startsWith("java.net.URLConnection")
                || name.startsWith("java.net.HttpURLConnection")
                || name.startsWith("java.nio.channels.Socket")
                || name.startsWith("javax.net.ssl")
                || name.startsWith("org.jsoup");
    }

    static boolean isFileReadClass(String name) {
        if (name == null) return false;
        return name.equals("java.io.File")
                || name.startsWith("java.io.FileReader")
                || name.startsWith("java.io.FileInputStream")
                || name.startsWith("java.nio.file.Files")
                || name.startsWith("java.nio.file.Path")
                || name.startsWith("java.nio.file.Paths");
    }

    static boolean isFileWriteClass(String name) {
        if (name == null) return false;
        return name.startsWith("java.io.FileWriter")
                || name.startsWith("java.io.FileOutputStream")
                || name.startsWith("java.io.RandomAccessFile")
                || name.startsWith("java.nio.channels.FileChannel");
    }

    private static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
