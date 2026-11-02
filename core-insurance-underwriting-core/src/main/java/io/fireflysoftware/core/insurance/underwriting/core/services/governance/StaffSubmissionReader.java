/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.models.entities.Submission;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface StaffSubmissionReader {
    Mono<Submission> read(UUID submissionId);
}
