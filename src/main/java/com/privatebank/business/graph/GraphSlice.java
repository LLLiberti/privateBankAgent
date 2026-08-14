package com.privatebank.business.graph;

import java.util.List;

public record GraphSlice(List<GraphRow> rows, boolean truncated) {
    public GraphSlice {
        rows = List.copyOf(rows);
    }
}
