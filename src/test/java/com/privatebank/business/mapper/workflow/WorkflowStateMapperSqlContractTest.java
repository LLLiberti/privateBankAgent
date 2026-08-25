package com.privatebank.business.mapper.workflow;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStateMapperSqlContractTest {

    @Test
    void reportCenterMysqlQuerySortsByEffectiveReportTimeBeforeStablePagination() throws Exception {
        Method method = WorkflowStateMapper.class.getMethod(
                "findForReportCenter", String.class, Long.class, String.class, int.class, int.class);

        String sql = Arrays.stream(method.getAnnotationsByType(Select.class))
                .filter(select -> select.databaseId().isEmpty())
                .findFirst()
                .map(select -> String.join("\n", select.value()))
                .orElseThrow();

        int reportExportedAt = sql.indexOf("PATH '$.reportExportedAt'");
        int filesArray = sql.indexOf("'$.files'");
        int generatedAt = sql.indexOf("generated_at DATETIME(6) PATH '$.generatedAt'");
        int workflowUpdatedAt = sql.indexOf("CAST(w.updated_at AS DATETIME(6))");
        int effectiveTimeDescending = sql.indexOf(") DESC,");
        int stableWorkflowId = sql.indexOf("w.workflow_id DESC", effectiveTimeDescending);
        int pagination = sql.indexOf("LIMIT #{pageSize} OFFSET #{offset}");

        assertThat(reportExportedAt).isPositive();
        assertThat(filesArray).isGreaterThan(reportExportedAt);
        assertThat(generatedAt).isGreaterThan(filesArray);
        assertThat(workflowUpdatedAt).isGreaterThan(generatedAt);
        assertThat(effectiveTimeDescending).isGreaterThan(workflowUpdatedAt);
        assertThat(stableWorkflowId).isGreaterThan(effectiveTimeDescending);
        assertThat(pagination).isGreaterThan(stableWorkflowId);
        assertThat(sql).contains(
                "ordinal_no FOR ORDINALITY",
                "NULLIF(TRIM(file_metadata.file_id), '') IS NOT NULL",
                "ORDER BY file_metadata.ordinal_no ASC",
                "COALESCE(");
    }
}
