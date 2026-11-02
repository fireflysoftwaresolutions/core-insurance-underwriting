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


package io.fireflysoftware.core.insurance.underwriting.web.controllers;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.insurance.underwriting.core.services.SubmissionService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.dtos.SubmissionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for underwriting submission received for a product and applicant.
 */
@RestController
@RequestMapping("/api/v1/submissions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Submissions", description = "Underwriting submission received for a product and applicant")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final org.springframework.beans.factory.ObjectProvider<io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffSubmissionReader> currentReads;
    private final io.fireflysoftware.core.insurance.underwriting.core.mappers.SubmissionMapper submissionMapper;

    @PostMapping("/filter")
    @Operation(summary = "Filter submissions", description = "Retrieve a page of submissions matching the filter")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of matching records"),
        @ApiResponse(responseCode = "400", description = "Invalid filter request", content = @Content)
    })
    public Mono<ResponseEntity<PaginationResponse<SubmissionDTO>>> filterSubmissions(
            @Parameter(description = "Filter criteria", required = true)
            @Valid @RequestBody FilterRequest<SubmissionDTO> filterRequest) {
        return submissionService.filterSubmissions(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(summary = "Create Submission", description = "Create a Submission")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Submission created", content = @Content(schema = @Schema(implementation = SubmissionDTO.class))),
        @ApiResponse(responseCode = "400", description = "Invalid data", content = @Content)
    })
    public Mono<ResponseEntity<SubmissionDTO>> createSubmission(
            @Parameter(description = "Submission to create", required = true)
            @Valid @RequestBody SubmissionDTO dto) {
        return submissionService.createSubmission(dto)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "governedQuoteBearer")
    @GetMapping("/{submissionId}")
    @Operation(summary = "Get Submission by id", description = "Retrieve one Submission")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Submission found", content = @Content(schema = @Schema(implementation = SubmissionDTO.class))),
        @ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public Mono<ResponseEntity<SubmissionDTO>> getSubmissionById(
            @Parameter(description = "Unique identifier of the Submission", required = true)
            @PathVariable UUID submissionId) {
        var reader=currentReads.getIfAvailable();
        if(reader==null)return Mono.error(new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Current submission read is not configured"));
        return reader.read(submissionId).map(submissionMapper::toDTO).map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorMap(SecurityException.class, rejected -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Current submission read authority required"));
    }

    @PutMapping("/{submissionId}")
    @Operation(summary = "Update Submission", description = "Update an existing Submission")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Submission updated", content = @Content(schema = @Schema(implementation = SubmissionDTO.class))),
        @ApiResponse(responseCode = "400", description = "Invalid data", content = @Content),
        @ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public Mono<ResponseEntity<SubmissionDTO>> updateSubmission(
            @Parameter(description = "Unique identifier of the Submission", required = true)
            @PathVariable UUID submissionId,
            @Parameter(description = "Updated Submission", required = true)
            @Valid @RequestBody SubmissionDTO dto) {
        return submissionService.updateSubmission(submissionId, dto)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{submissionId}")
    @Operation(summary = "Delete Submission", description = "Delete a Submission")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Submission deleted"),
        @ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public Mono<ResponseEntity<Void>> deleteSubmission(
            @Parameter(description = "Unique identifier of the Submission", required = true)
            @PathVariable UUID submissionId) {
        return submissionService.deleteSubmission(submissionId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
