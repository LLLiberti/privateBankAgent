package com.privatebank.business.service.customer;

import org.springframework.stereotype.Service;

@Service
public class RedactionService {

    public String redact(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)", "$1****$2")
                .replaceAll("(?i)([a-z0-9._%+-]{2})[a-z0-9._%+-]*(@[a-z0-9.-]+\\.[a-z]{2,})", "$1***$2")
                .replaceAll("(?<!\\d)(\\d{6})\\d{8}([0-9Xx]{4})(?!\\d)", "$1********$2");
    }
}
