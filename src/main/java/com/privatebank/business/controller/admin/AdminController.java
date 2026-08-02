package com.privatebank.business.controller.admin;

import com.privatebank.business.dto.admin.AdminWorkflowResponse;
import com.privatebank.business.dto.admin.ConfigurationCandidateRequest;
import com.privatebank.business.dto.admin.ConfigurationPublishRequest;
import com.privatebank.business.service.admin.AdminService;
import com.privatebank.business.service.admin.ConfigurationRegistry;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.dto.document.DocumentResponse;
import com.privatebank.business.service.document.DocumentService;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.entity.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ConfigurationRegistry configurationRegistry;
    private final DocumentService documentService;

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return adminService.overview();
    }

    @GetMapping("/configurations/{type}")
    public Map<String, Object> configuration(@PathVariable String type) {
        return configurationRegistry.current(type);
    }

    @PostMapping("/configurations/{type}/validate")
    public Map<String, Object> validate(
            @PathVariable String type,
            @Valid @RequestBody ConfigurationCandidateRequest request) {
        return configurationRegistry.validate(type, request.configuration());
    }

    @PostMapping("/configurations/{type}/publish")
    public Map<String, Object> publish(
            @PathVariable String type,
            @Valid @RequestBody ConfigurationPublishRequest request) {
        return configurationRegistry.publish(type, request.candidateId());
    }

    @PostMapping("/knowledge/documents")
    public DocumentResponse uploadKnowledge(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam MultipartFile file) {
        return documentService.uploadKnowledge(principal, file);
    }

    @PostMapping("/knowledge/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reindex(@RequestBody(required = false) Map<String, Object> request) {
        return Map.of("status", "ACCEPTED", "execution", "EXTERNAL_DATA_GOVERNANCE_REQUIRED");
    }

    @GetMapping("/workflows")
    public PageResponse<AdminWorkflowResponse> workflows(
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return adminService.workflows(status, pageNo, pageSize);
    }

    @PostMapping("/demo-data/reset")
    public Map<String, Object> reset(@RequestParam String confirmation) {
        return adminService.validateReset(confirmation);
    }
}
