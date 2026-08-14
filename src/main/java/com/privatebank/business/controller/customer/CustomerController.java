package com.privatebank.business.controller.customer;

import com.privatebank.business.dto.customer.CustomerDetailResponse;
import com.privatebank.business.dto.customer.CustomerPanoramaResponse;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.dto.customer.EvidenceResponse;
import com.privatebank.business.dto.customer.graph.GraphNodeType;
import com.privatebank.business.dto.customer.graph.GraphResponse;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.service.customer.CustomerGraphService;
import com.privatebank.business.service.customer.CustomerService;
import com.privatebank.business.security.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerGraphService customerGraphService;

    @GetMapping("/customers")
    public PageResponse<CustomerSummaryResponse> customers(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return customerService.list(principal, keyword, pageNo, pageSize);
    }

    @GetMapping("/customers/{customerId}")
    public CustomerDetailResponse detail(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId) {
        return customerService.detail(principal, customerId);
    }

    @GetMapping("/customers/{customerId}/panorama")
    public CustomerPanoramaResponse panorama(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOfTime) {
        return customerService.panorama(principal, customerId, asOfTime);
    }

    @GetMapping("/customers/{customerId}/graph")
    public GraphResponse graph(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam(required = false) Set<GraphNodeType> types,
            @RequestParam(required = false) @Min(1) Integer maxNodes) {
        return customerGraphService.initialGraph(principal, customerId, types, maxNodes);
    }

    @GetMapping("/customers/{customerId}/graph/nodes/{nodeId}/neighbors")
    public GraphResponse graphNeighbors(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId,
            @PathVariable String nodeId,
            @RequestParam(required = false) Set<GraphNodeType> types,
            @RequestParam(required = false) @Min(1) Integer maxNodes) {
        return customerGraphService.neighbors(principal, customerId, nodeId, types, maxNodes);
    }

    @GetMapping("/evidence/{sourceRef}")
    public EvidenceResponse evidence(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long sourceRef) {
        return customerService.evidence(principal, sourceRef);
    }
}
