package com.privatebank.common.api;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int pageNo, int pageSize, boolean hasMore) {

    public static <T> PageResponse<T> of(List<T> items, long total, int pageNo, int pageSize) {
        return new PageResponse<>(items, total, pageNo, pageSize, (long) pageNo * pageSize < total);
    }
}
