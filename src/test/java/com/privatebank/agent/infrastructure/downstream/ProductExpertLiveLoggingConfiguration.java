package com.privatebank.agent.infrastructure.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.downstream.ProductKnowledgeSearchService;
import com.privatebank.agent.application.downstream.ProductKnowledgeSearchTool;
import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@TestConfiguration(proxyBeanMethods = false)
class ProductExpertLiveLoggingConfiguration {

    private static final List<Map<String, Object>> TOOL_REQUESTS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Boolean> SEARCH_TOOL_ACTIVE = ThreadLocal.withInitial(() -> false);

    static void clearCapturedLogs() {
        TOOL_REQUESTS.clear();
        SEARCH_TOOL_ACTIVE.remove();
    }

    static List<Map<String, Object>> capturedToolRequests() {
        return List.copyOf(TOOL_REQUESTS);
    }

    @Bean
    @Primary
    ProductKnowledgeSearchTool loggingProductKnowledgeSearchTool(
            ProductKnowledgeSearchService searchService,
            ObjectMapper objectMapper) {
        return new ProductKnowledgeSearchTool(searchService) {
            @Override
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
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("queries", queries);
                request.put("productIds", productIds);
                request.put("riskLevel", riskLevel);
                request.put("saleStatus", saleStatus);
                TOOL_REQUESTS.add(request);
                System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_REQUEST] params=%s%n", json(objectMapper, request));

                SEARCH_TOOL_ACTIVE.set(true);
                try {
                    ProductKnowledgeSearchResult result = super.search(queries, productIds, riskLevel, saleStatus);
                    System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_RESULT] result=%s%n",
                            json(objectMapper, result));
                    return result;
                } finally {
                    SEARCH_TOOL_ACTIVE.remove();
                }
            }
        };
    }

    @Bean
    Interceptor searchProductKnowledgeSqlLoggingInterceptor() {
        return new SearchProductKnowledgeSqlLoggingInterceptor();
    }

    private static String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法输出 search_product_knowledge 测试请求日志", exception);
        }
    }

    @Intercepts({
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
            ),
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {
                            MappedStatement.class,
                            Object.class,
                            RowBounds.class,
                            ResultHandler.class,
                            CacheKey.class,
                            BoundSql.class
                    }
            )
    })
    static class SearchProductKnowledgeSqlLoggingInterceptor implements Interceptor {

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (Boolean.TRUE.equals(SEARCH_TOOL_ACTIVE.get())) {
                MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
                Object parameterObject = invocation.getArgs()[1];
                BoundSql boundSql = invocation.getArgs().length == 6
                        ? (BoundSql) invocation.getArgs()[5]
                        : statement.getBoundSql(parameterObject);
                List<Object> parameters = parameterValues(statement, boundSql, parameterObject);
                String sqlTemplate = normalizeSql(boundSql.getSql());

                System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_SQL] statement=%s%n", statement.getId());
                System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_SQL] template=%s%n", sqlTemplate);
                System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_SQL] parameters=%s%n", parameters);
                System.out.printf("[SEARCH_PRODUCT_KNOWLEDGE_SQL] finalSql=%s%n",
                        bindParameters(sqlTemplate, parameters));
            }
            return invocation.proceed();
        }

        private List<Object> parameterValues(
                MappedStatement statement,
                BoundSql boundSql,
                Object parameterObject) {
            List<Object> values = new ArrayList<>();
            for (ParameterMapping mapping : boundSql.getParameterMappings()) {
                if (mapping.getMode() != ParameterMode.OUT) {
                    values.add(parameterValue(statement, boundSql, parameterObject, mapping.getProperty()));
                }
            }
            return values;
        }

        private Object parameterValue(
                MappedStatement statement,
                BoundSql boundSql,
                Object parameterObject,
                String property) {
            if (boundSql.hasAdditionalParameter(property)) {
                return boundSql.getAdditionalParameter(property);
            }
            if (parameterObject == null) {
                return null;
            }
            if (statement.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
                return parameterObject;
            }
            MetaObject metaObject = statement.getConfiguration().newMetaObject(parameterObject);
            return metaObject.hasGetter(property) ? metaObject.getValue(property) : "<unresolved:" + property + ">";
        }

        private String normalizeSql(String sql) {
            return sql.replaceAll("\\s+", " ").trim();
        }

        private String bindParameters(String sqlTemplate, List<Object> parameters) {
            String sql = sqlTemplate;
            for (Object parameter : parameters) {
                int placeholder = sql.indexOf('?');
                if (placeholder < 0) {
                    break;
                }
                sql = sql.substring(0, placeholder) + sqlLiteral(parameter) + sql.substring(placeholder + 1);
            }
            return sql;
        }

        private String sqlLiteral(Object value) {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
    }
}
