package com.privatebank.document.api;

import com.privatebank.common.api.PageResponse;
import com.privatebank.document.application.DocumentService;
import com.privatebank.security.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/customers/{customerId}/documents")
    public PageResponse<DocumentResponse> list(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return documentService.list(principal, customerId, pageNo, pageSize);
    }

    @PostMapping("/customers/{customerId}/documents")
    public DocumentResponse upload(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "SUPPLEMENTARY") @Size(max = 32) String documentType) {
        return documentService.upload(principal, customerId, file, documentType);
    }

    @GetMapping("/documents/{documentId}")
    public DocumentResponse get(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String documentId) {
        return documentService.get(principal, documentId);
    }
}
