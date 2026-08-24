package com.privatebank.business.controller.admin;

import com.privatebank.business.dto.admin.AdminWorkflowDeleteRequest;
import com.privatebank.business.dto.admin.AdminWorkflowDeleteResponse;
import com.privatebank.business.dto.admin.AdminWorkflowResponse;
import com.privatebank.business.dto.product.ProductCatalogResponse;
import com.privatebank.business.dto.admin.ConfigurationCandidateRequest;
import com.privatebank.business.dto.admin.ConfigurationPublishRequest;
import com.privatebank.business.dto.admin.CustomerManagerResponse;
import com.privatebank.business.dto.admin.CustomerScopeResponse;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesRequest;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesResponse;
import com.privatebank.business.service.admin.AdminService;
import com.privatebank.business.service.admin.AdminWorkflowCleanupService;
import com.privatebank.business.service.admin.ConfigurationRegistry;
import com.privatebank.business.service.admin.CustomerScopeAdminService;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.dto.document.DocumentResponse;
import com.privatebank.business.service.document.DocumentService;
import com.privatebank.business.service.product.ProductService;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final AdminWorkflowCleanupService adminWorkflowCleanupService;
    private final ConfigurationRegistry configurationRegistry;
    private final CustomerScopeAdminService customerScopeAdminService;
    private final DocumentService documentService;
    private final ProductService productService;

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

    @DeleteMapping("/workflows/{workflowId}")
    public AdminWorkflowDeleteResponse deleteWorkflow(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable @Size(max = 64) String workflowId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody AdminWorkflowDeleteRequest request) {
        return adminWorkflowCleanupService.delete(principal, workflowId, idempotencyKey, request);
    }

    @GetMapping("/products")
    public PageResponse<ProductCatalogResponse> products(
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 100) String productCategory,
            @RequestParam(required = false) @Size(max = 20) String riskLevel,
            @RequestParam(required = false) @Size(max = 30) String productStatus,
            @RequestParam(required = false) @Size(max = 10) String currency,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return productService.listForAdministration(
                keyword, productCategory, riskLevel, productStatus, currency, pageNo, pageSize);
    }

    @GetMapping("/customer-managers")
    public PageResponse<CustomerManagerResponse> customerManagers(
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return customerScopeAdminService.customerManagers(keyword, pageNo, pageSize);
    }

    @GetMapping("/customer-managers/{userId}/customer-scopes")
    public PageResponse<CustomerScopeResponse> customerScopes(
            @PathVariable @Size(max = 64) String userId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return customerScopeAdminService.customerScopes(userId, includeInactive, pageNo, pageSize);
    }

    @PutMapping("/customer-managers/{userId}/customer-scopes")
    public ReplaceCustomerScopesResponse replaceCustomerScopes(
            @PathVariable @Size(max = 64) String userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReplaceCustomerScopesRequest request) {
        return customerScopeAdminService.replaceCustomerScopes(userId, idempotencyKey, request);
    }

    @PostMapping("/demo-data/reset")
    public Map<String, Object> reset(@RequestParam String confirmation) {
        return adminService.validateReset(confirmation);
    }
}
