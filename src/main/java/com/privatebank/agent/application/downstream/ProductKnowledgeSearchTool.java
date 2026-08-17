package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AgentScope-visible tool.  It intentionally exposes only the search result,
 * never the underlying ES/Qdrant/MySQL connection details.
 */
@Component
@RequiredArgsConstructor
public class ProductKnowledgeSearchTool {

    private final ProductKnowledgeSearchService searchService;

    public ProductKnowledgeSearchResult search(
            String query,
            List<String> productIds,
            String riskLevel,
            String region,
            String saleStatus) {
        return searchService.search(query, productIds, riskLevel, region, saleStatus);
    }
}
