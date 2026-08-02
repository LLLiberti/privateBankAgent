package com.privatebank.common.api;

import java.util.Map;

public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
}
