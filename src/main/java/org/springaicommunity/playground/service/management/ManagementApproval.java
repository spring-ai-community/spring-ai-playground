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

public record ManagementApproval(ApprovalLevel level, String summary, boolean highRisk) {

    public static final ManagementApproval READ_ONLY = new ManagementApproval(ApprovalLevel.READ, "", false);

    public static ManagementApproval write(String summary) {
        return new ManagementApproval(ApprovalLevel.WRITE, summary, false);
    }

    public static ManagementApproval destructive(String summary) {
        return new ManagementApproval(ApprovalLevel.DESTRUCTIVE, summary, false);
    }

    public ManagementApproval markHighRisk() {
        return new ManagementApproval(this.level, this.summary, true);
    }

}
