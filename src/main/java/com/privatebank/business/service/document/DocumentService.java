package com.privatebank.business.service.document;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.dto.document.DocumentResponse;
import com.privatebank.business.entity.document.DocumentRecord;
import com.privatebank.business.mapper.document.DocumentRecordMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRecordMapper documentMapper;
    private final CurrentUserService currentUserService;
    private final FileStorageService storageService;

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(
            CurrentUserPrincipal principal, Long personId, int pageNo, int pageSize) {
        currentUserService.requireCustomerAccess(principal, personId);
        Page<DocumentRecord> page = documentMapper.selectPage(
                new Page<>(pageNo, pageSize),
                Wrappers.<DocumentRecord>lambdaQuery()
                        .eq(DocumentRecord::getPersonId, personId)
                        .orderByDesc(DocumentRecord::getUploadTime));
        return PageResponse.of(page.getRecords().stream().map(DocumentResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }

    @Transactional
    public DocumentResponse upload(
            CurrentUserPrincipal principal, Long personId, MultipartFile file, String documentType) {
        currentUserService.requireCustomerAccess(principal, personId);
        String documentId = "DOC-" + UUID.randomUUID();
        FileStorageService.StoredFile stored = storageService.store(personId, documentId, file);
        try {
            DocumentRecord document = new DocumentRecord();
            document.setDocumentId(documentId);
            document.setPersonId(personId);
            document.setFileName(stored.fileName());
            document.setFileType(stored.fileType());
            document.setFilePath(stored.path());
            document.setUploadTime(LocalDateTime.now());
            document.setParseStatus("PENDING");
            document.setFactCount(0);
            insert(document);
            return DocumentResponse.from(document);
        } catch (RuntimeException exception) {
            storageService.deleteQuietly(stored.path());
            throw exception;
        }
    }

    @Transactional
    public DocumentResponse uploadKnowledge(CurrentUserPrincipal principal, MultipartFile file) {
        if (!principal.isSystemAdmin()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "仅系统管理员可上传知识资料");
        }
        String documentId = "DOC-" + UUID.randomUUID();
        FileStorageService.StoredFile stored = storageService.store(null, documentId, file);
        try {
            DocumentRecord document = new DocumentRecord();
            document.setDocumentId(documentId);
            document.setPersonId(null);
            document.setFileName(stored.fileName());
            document.setFileType(stored.fileType());
            document.setFilePath(stored.path());
            document.setUploadTime(LocalDateTime.now());
            document.setParseStatus("PENDING");
            document.setFactCount(0);
            insert(document);
            return DocumentResponse.from(document);
        } catch (RuntimeException exception) {
            storageService.deleteQuietly(stored.path());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(CurrentUserPrincipal principal, String documentId) {
        DocumentRecord document = require(documentId);
        if (document.getPersonId() == null) {
            if (!principal.isSystemAdmin()) {
                throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "无权访问该知识文档");
            }
        } else {
            currentUserService.requireCustomerAccess(principal, document.getPersonId());
        }
        return DocumentResponse.from(document);
    }

    public DocumentRecord require(String documentId) {
        DocumentRecord document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private void insert(DocumentRecord document) {
        if (documentMapper.insert(document) != 1) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "文档信息保存失败");
        }
    }
}
