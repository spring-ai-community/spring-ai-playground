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

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public record ManagementTool(String name, String description, String category, Class<?> inputType,
                             Class<?> dataType, ApprovalLevel level, boolean idempotent, boolean openWorld,
                             Function<Map<String, Object>, ManagementApproval> approval,
                             BiFunction<Map<String, Object>, ManagementCaller, ManagementResult> handler) {

    private static final JsonMapper ARGUMENT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    public ManagementApproval approvalFor(Map<String, Object> arguments) {
        return this.approval.apply(arguments);
    }

    public ManagementResult handle(Map<String, Object> arguments, ManagementCaller caller) {
        return this.handler.apply(arguments, caller);
    }

    public static <I> Builder<I> named(String name, String category, Class<I> inputType) {
        return new Builder<>(name, category, inputType);
    }

    public static final class Builder<I> {

        private final String name;
        private final String category;
        private final Class<I> inputType;
        private String description = "";
        private Class<?> dataType;
        private ApprovalLevel level = ApprovalLevel.READ;
        private boolean idempotent;
        private boolean openWorld;
        private Function<I, ManagementApproval> approval;

        private Builder(String name, String category, Class<I> inputType) {
            this.name = name;
            this.category = category;
            this.inputType = inputType;
        }

        public Builder<I> description(String value) {
            this.description = value;
            return this;
        }

        public Builder<I> data(Class<?> value) {
            this.dataType = value;
            return this;
        }

        public Builder<I> idempotent() {
            this.idempotent = true;
            return this;
        }

        public Builder<I> openWorld() {
            this.openWorld = true;
            return this;
        }

        public Builder<I> write(Function<I, String> summary) {
            this.level = ApprovalLevel.WRITE;
            this.approval = input -> ManagementApproval.write(summary.apply(input));
            return this;
        }

        public Builder<I> destructive(Function<I, String> summary) {
            this.level = ApprovalLevel.DESTRUCTIVE;
            this.approval = input -> ManagementApproval.destructive(summary.apply(input));
            return this;
        }

        public Builder<I> approval(ApprovalLevel baseLevel, Function<I, ManagementApproval> planner) {
            this.level = baseLevel;
            this.approval = planner;
            return this;
        }

        public ManagementTool handle(BiFunction<I, ManagementCaller, ManagementResult> handler) {
            Function<Map<String, Object>, ManagementApproval> planner = arguments ->
                    this.approval == null ? ManagementApproval.READ_ONLY : this.approval.apply(convert(arguments));
            return new ManagementTool(this.name, this.description, this.category, this.inputType, this.dataType,
                    this.level, this.idempotent, this.openWorld, planner,
                    (arguments, caller) -> handler.apply(convert(arguments), caller));
        }

        private I convert(Map<String, Object> arguments) {
            try {
                return ARGUMENT_MAPPER.convertValue(arguments == null ? Map.of() : arguments, this.inputType);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid arguments for '" + this.name + "': "
                        + rootMessage(e), e);
            }
        }

        private static String rootMessage(Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String message = cause.getMessage();
            return message == null ? cause.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
        }

    }

}
