/*
 * Copyright 2026 Firefly Software Foundation
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


package io.fireflysoftware.core.insurance.underwriting.core.services.impl;

import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.SubmissionStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed moves for a submission. A received submission is the only status that can be removed.
 */
final class SubmissionLifecycleImpl {

    private static final Map<SubmissionStatus, Set<SubmissionStatus>> ALLOWED = Map.of(
            SubmissionStatus.RECEIVED, EnumSet.of(
                    SubmissionStatus.IN_CLEARANCE, SubmissionStatus.DECLINED, SubmissionStatus.WITHDRAWN),
            SubmissionStatus.IN_CLEARANCE, EnumSet.of(
                    SubmissionStatus.REFERRED, SubmissionStatus.QUOTED,
                    SubmissionStatus.DECLINED, SubmissionStatus.WITHDRAWN),
            SubmissionStatus.REFERRED, EnumSet.of(
                    SubmissionStatus.IN_CLEARANCE, SubmissionStatus.QUOTED,
                    SubmissionStatus.DECLINED, SubmissionStatus.WITHDRAWN),
            SubmissionStatus.QUOTED, EnumSet.of(
                    SubmissionStatus.BOUND, SubmissionStatus.DECLINED, SubmissionStatus.WITHDRAWN)
    );

    private SubmissionLifecycleImpl() {
    }

    static boolean canTransition(SubmissionStatus from, SubmissionStatus to) {
        if (from == to) {
            return true;
        }
        Set<SubmissionStatus> next = ALLOWED.get(from);
        return next != null && next.contains(to);
    }
}
