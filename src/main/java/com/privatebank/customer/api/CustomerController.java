package com.privatebank.customer.api;

import com.privatebank.common.api.PageResponse;
import com.privatebank.customer.application.CustomerService;
import com.privatebank.security.CurrentUserPrincipal;
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

@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

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

    @GetMapping("/evidence/{sourceRef}")
    public EvidenceResponse evidence(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long sourceRef) {
        return customerService.evidence(principal, sourceRef);
    }
}
