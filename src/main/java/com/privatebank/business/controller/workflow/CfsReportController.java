package com.privatebank.business.controller.workflow;

import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.dto.workflow.CfsReportCenterItemResponse;
import com.privatebank.business.dto.workflow.CfsReportPreviewResponse;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.workflow.WorkflowService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/cfs/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER_MANAGER')")
public class CfsReportController {

    private final WorkflowService workflowService;

    @GetMapping
    public PageResponse<CfsReportCenterItemResponse> reports(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam(required = false) @Positive Long customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return workflowService.reportCenter(principal, customerId, keyword, pageNo, pageSize);
    }

    @GetMapping("/{workflowId}/preview")
    public CfsReportPreviewResponse preview(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId) {
        return workflowService.reportPreview(principal, workflowId);
    }
}
