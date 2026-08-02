package com.privatebank.business.dto.common;

import java.util.Map;

public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
}
