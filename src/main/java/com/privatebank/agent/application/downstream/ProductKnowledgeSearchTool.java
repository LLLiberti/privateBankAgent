package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
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

    @Tool(
            name = "search_product_knowledge",
            description = "根据 KYC 提取的产品需求关键词、风险等级和销售状态，检索候选产品和产品知识"
    )
    public ProductKnowledgeSearchResult search(
            @ToolParam(name = "queries", description = "从 KYC 中提取的产品需求关键词列表，不要求每个关键词都命中产品元数据", required = true)
            List<String> queries,
            @ToolParam(name = "productIds", description = "指定的产品ID列表，可为空", required = false)
            List<String> productIds,
            @ToolParam(name = "riskLevel", description = "产品风险等级，例如 PR2；可为空", required = false)
            String riskLevel,
            @ToolParam(name = "saleStatus", description = "产品状态，默认 ACTIVE", required = false)
            String saleStatus) {
        return searchService.search(queries, productIds, riskLevel, saleStatus);
    }
}
