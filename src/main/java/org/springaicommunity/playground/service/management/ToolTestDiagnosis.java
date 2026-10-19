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
package org.springaicommunity.playground.service.management;

import org.springaicommunity.playground.service.tool.ToolSpec.SandboxOverrides;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ToolTestDiagnosis {

    private static final Pattern DENIED_HOST = Pattern.compile("host not in allowlist: (\\S+)");
    private static final Pattern MISSING_ENV = Pattern.compile("env var not set: \\$\\{(\\w+)}");
    private static final Pattern UNDEFINED_NAME = Pattern.compile("ReferenceError: (\\w+) is not defined");

    private ToolTestDiagnosis() {
    }

    static List<String> hints(String error, String code, SandboxOverrides sandbox, List<String> skippedParams,
            boolean returnedNothing) {
        List<String> hints = new ArrayList<>();
        String failure = error == null ? "" : error;
        if (!failure.isBlank()) {
            failureHints(failure, code == null ? "" : code, sandbox, hints);
        } else if (returnedNothing) {
            hints.add("The code ran but returned nothing. End the code with a return statement that gives the "
                    + "result object, for example: return { rate: 1.1 };");
        }
        if (!skippedParams.isEmpty()) {
            hints.add("No testValue for " + skippedParams + ", so they were not passed to the code. Give every "
                    + "parameter that the code needs a testValue, otherwise a pass proves little.");
        }
        return hints;
    }

    private static void failureHints(String failure, String code, SandboxOverrides sandbox, List<String> hints) {
        Matcher missingEnv = MISSING_ENV.matcher(failure);
        if (missingEnv.find()) {
            hints.add("The environment variable " + missingEnv.group(1) + " is not set on the machine that runs this "
                    + "app, so a tool that needs it cannot pass its test yet. Do not turn the secret into a "
                    + "parameter and do not write it into the code. Stop and ask the user to set "
                    + missingEnv.group(1) + " in the environment of the app and restart it, then run "
                    + "saip_tools_test again.");
        }
        Matcher undefinedName = UNDEFINED_NAME.matcher(failure);
        if (undefinedName.find()) {
            String name = undefinedName.group(1);
            hints.add("fetch".equals(name) ? fetchBlockedHint()
                    : "'" + name + "' is not defined. Parameters and static variables become globals only when "
                            + "they are declared in params or staticVariables with exactly that name.");
        }
        Matcher deniedHost = DENIED_HOST.matcher(failure);
        if (deniedHost.find()) {
            hints.add("The host " + deniedHost.group(1) + " is not in sandbox.hostsAllow. Add exactly that host "
                    + "(not '*') and keep networkMode ALLOWLIST.");
        }
        if (failure.contains("host in denylist") || failure.contains("literal-private-ip")
                || failure.contains("resolved-private-ip")) {
            hints.add("The sandbox refuses calls to private or denied addresses. Use a public https endpoint.");
        }
        if (failure.contains("timed out") || failure.contains("limit exceeded")) {
            hints.add("The code ran too long or executed too many statements. Reduce the work, avoid large loops "
                    + "and limit the size of downloaded data with fetch's maxLength option.");
        }
        boolean usesFileHelper = code.contains("safety.fs");
        boolean fileAccess = sandbox != null && (Boolean.TRUE.equals(sandbox.fileRead())
                || Boolean.TRUE.equals(sandbox.fileWrite()));
        if (usesFileHelper && !fileAccess) {
            hints.add("safety.fs exists only when the sandbox allows file access: set sandbox.fileRead (L3) or "
                    + "sandbox.fileWrite (L4, approval needed) to true, and use the least that works.");
        }
        if (code.contains("Java.type") && failure.toLowerCase().contains("access")) {
            hints.add("Java classes are restricted by the sandbox allowlist. Prefer the runtime helpers (fetch, "
                    + "safety.parser, safety.fs, crypto) instead of Java.type; extra classes raise the risk level "
                    + "and need approval.");
        }
        if (hints.isEmpty()) {
            hints.add("Read the error and the console output, fix the code and run saip_tools_test again. "
                    + "Read saip://guide/tool-authoring/mistakes for common causes.");
        }
    }

    private static String fetchBlockedHint() {
        return "fetch is not available because the sandbox blocks the network. Set sandbox.networkMode to ALLOWLIST "
                + "and list the hosts in sandbox.hostsAllow (risk L3). Avoid OPEN, it is L4 and needs approval.";
    }

}
