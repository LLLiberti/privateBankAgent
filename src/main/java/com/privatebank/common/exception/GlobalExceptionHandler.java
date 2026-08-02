package com.privatebank.common.exception;

import com.privatebank.common.api.ApiError;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "请求参数校验失败", details);
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> handleConstraint(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "无权执行该操作", Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimistic(ObjectOptimisticLockingFailureException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT, "资源已被其他请求更新，请刷新后重试", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDuplicate(DataIntegrityViolationException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT, "数据约束冲突", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "上传文件超过大小限制", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "系统内部错误", Map.of());
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ApiError(code.name(), message, MDC.get("traceId"), details));
    }
}
