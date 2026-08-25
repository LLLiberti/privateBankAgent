package com.privatebank.business.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.privatebank.business.dto.workflow.CfsReportWorkflowRow;
import com.privatebank.business.dto.workflow.CustomerManagerWorkflowResponse;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WorkflowStateMapper extends BaseMapper<WorkflowState> {

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM workflow_state w
             WHERE w.created_by = #{userId}
               AND EXISTS (
                   SELECT 1
                     FROM user_customer_scope ucs
                    WHERE ucs.user_id = #{userId}
                      AND ucs.person_id = w.person_id
                      AND ucs.scope_status = 1
               )
            <if test="customerId != null">
               AND w.person_id = #{customerId}
            </if>
            <if test="status != null">
               AND w.workflow_status = #{status}
            </if>
            </script>
            """)
    long countForCustomerManager(
            @Param("userId") String userId,
            @Param("customerId") Long customerId,
            @Param("status") WorkflowStatus status);

    @Select("""
            <script>
            SELECT w.workflow_id AS workflowId,
                   w.person_id AS customerId,
                   COALESCE(NULLIF(p.display_name, ''), p.full_name) AS customerName,
                   w.workflow_status AS workflowStatus,
                   w.template_id AS templateId,
                   w.as_of_date AS asOfDate,
                   w.updated_at AS updatedAt
              FROM workflow_state w
              JOIN person p ON p.person_id = w.person_id
             WHERE w.created_by = #{userId}
               AND EXISTS (
                   SELECT 1
                     FROM user_customer_scope ucs
                    WHERE ucs.user_id = #{userId}
                      AND ucs.person_id = w.person_id
                      AND ucs.scope_status = 1
               )
            <if test="customerId != null">
               AND w.person_id = #{customerId}
            </if>
            <if test="status != null">
               AND w.workflow_status = #{status}
            </if>
             ORDER BY w.updated_at DESC, w.workflow_id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<CustomerManagerWorkflowResponse> findForCustomerManager(
            @Param("userId") String userId,
            @Param("customerId") Long customerId,
            @Param("status") WorkflowStatus status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM workflow_state w
              JOIN person p ON p.person_id = w.person_id
             WHERE w.created_by = #{userId}
               AND w.workflow_status IN ('WAITING_INPUT', 'WAITING_REVIEW', 'GENERATING_OUTPUT', 'COMPLETED', 'FAILED')
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact compliance
                    WHERE compliance.workflow_id = w.workflow_id
                      AND compliance.agent_type = 'COMPLIANCE_CHECK'
                      AND UPPER(compliance.compliance_result) IN ('PASS', 'REVIEW_REQUIRED')
                      AND compliance.version = (
                          SELECT MAX(latest_compliance.version)
                            FROM agent_artifact latest_compliance
                           WHERE latest_compliance.workflow_id = w.workflow_id
                             AND latest_compliance.agent_type = 'COMPLIANCE_CHECK'
                      )
               )
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact cfs
                    WHERE cfs.workflow_id = w.workflow_id
                      AND cfs.agent_type = 'SOLUTION_DESIGN'
               )
               AND EXISTS (
                   SELECT 1
                     FROM user_customer_scope ucs
                    WHERE ucs.user_id = #{userId}
                      AND ucs.person_id = w.person_id
                      AND ucs.scope_status = 1
               )
            <if test="customerId != null">
               AND w.person_id = #{customerId}
            </if>
            <if test="keyword != null and keyword != ''">
               AND (
                   w.workflow_id LIKE CONCAT('%', #{keyword}, '%')
                   OR w.template_id LIKE CONCAT('%', #{keyword}, '%')
                   OR COALESCE(NULLIF(p.display_name, ''), p.full_name) LIKE CONCAT('%', #{keyword}, '%')
               )
            </if>
            </script>
            """)
    long countForReportCenter(
            @Param("userId") String userId,
            @Param("customerId") Long customerId,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT w.workflow_id AS workflowId,
                   w.person_id AS customerId,
                   COALESCE(NULLIF(p.display_name, ''), p.full_name) AS customerName,
                   w.workflow_status AS workflowStatus,
                   w.template_id AS templateId,
                   w.as_of_date AS asOfDate,
                   w.error_code AS errorCode,
                   w.error_message AS errorMessage,
                   w.updated_at AS updatedAt
              FROM workflow_state w
              JOIN person p ON p.person_id = w.person_id
              LEFT JOIN agent_artifact latest_cfs
                ON latest_cfs.artifact_id = (
                    SELECT candidate_cfs.artifact_id
                      FROM agent_artifact candidate_cfs
                     WHERE candidate_cfs.workflow_id = w.workflow_id
                       AND candidate_cfs.agent_type = 'SOLUTION_DESIGN'
                     ORDER BY candidate_cfs.version DESC
                     LIMIT 1
                )
              LEFT JOIN agent_artifact latest_compliance
                ON latest_compliance.artifact_id = (
                    SELECT candidate_compliance.artifact_id
                      FROM agent_artifact candidate_compliance
                     WHERE candidate_compliance.workflow_id = w.workflow_id
                       AND candidate_compliance.agent_type = 'COMPLIANCE_CHECK'
                     ORDER BY candidate_compliance.version DESC
                     LIMIT 1
                )
             WHERE w.created_by = #{userId}
               AND w.workflow_status IN ('WAITING_INPUT', 'WAITING_REVIEW', 'GENERATING_OUTPUT', 'COMPLETED', 'FAILED')
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact compliance
                    WHERE compliance.workflow_id = w.workflow_id
                      AND compliance.agent_type = 'COMPLIANCE_CHECK'
                      AND UPPER(compliance.compliance_result) IN ('PASS', 'REVIEW_REQUIRED')
                      AND compliance.version = (
                          SELECT MAX(latest_compliance.version)
                            FROM agent_artifact latest_compliance
                           WHERE latest_compliance.workflow_id = w.workflow_id
                             AND latest_compliance.agent_type = 'COMPLIANCE_CHECK'
                      )
               )
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact cfs
                    WHERE cfs.workflow_id = w.workflow_id
                      AND cfs.agent_type = 'SOLUTION_DESIGN'
               )
               AND EXISTS (
                   SELECT 1
                     FROM user_customer_scope ucs
                    WHERE ucs.user_id = #{userId}
                      AND ucs.person_id = w.person_id
                      AND ucs.scope_status = 1
               )
            <if test="customerId != null">
               AND w.person_id = #{customerId}
            </if>
            <if test="keyword != null and keyword != ''">
               AND (
                   w.workflow_id LIKE CONCAT('%', #{keyword}, '%')
                   OR w.template_id LIKE CONCAT('%', #{keyword}, '%')
                   OR COALESCE(NULLIF(p.display_name, ''), p.full_name) LIKE CONCAT('%', #{keyword}, '%')
               )
            </if>
             ORDER BY COALESCE(
                 CASE
                     WHEN JSON_VALID(latest_cfs.result)
                      AND JSON_TYPE(IF(JSON_VALID(latest_cfs.result), latest_cfs.result, JSON_OBJECT())) = 'OBJECT'
                      AND JSON_VALID(latest_compliance.result)
                      AND UPPER(latest_compliance.compliance_result) IN ('PASS', 'REVIEW_REQUIRED')
                      AND COALESCE(
                          NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(
                              IF(JSON_VALID(latest_compliance.result), latest_compliance.result, JSON_OBJECT()),
                              '$.cfsArtifactRef'))), ''),
                          NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(
                              IF(JSON_VALID(latest_compliance.result), latest_compliance.result, JSON_OBJECT()),
                              '$.cfsArtifactId'))), '')
                      ) = latest_cfs.artifact_id
                     THEN (
                         SELECT report_metadata.report_exported_at
                           FROM JSON_TABLE(
                               IF(JSON_VALID(latest_cfs.result), latest_cfs.result, JSON_OBJECT()),
                               '$' COLUMNS (
                                   report_exported_at DATETIME(6) PATH '$.reportExportedAt'
                                       NULL ON EMPTY NULL ON ERROR
                               )
                           ) AS report_metadata
                         LIMIT 1
                     )
                 END,
                 CASE
                     WHEN JSON_VALID(latest_cfs.result)
                      AND JSON_TYPE(IF(JSON_VALID(latest_cfs.result), latest_cfs.result, JSON_OBJECT())) = 'OBJECT'
                      AND JSON_VALID(latest_compliance.result)
                      AND UPPER(latest_compliance.compliance_result) IN ('PASS', 'REVIEW_REQUIRED')
                      AND COALESCE(
                          NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(
                              IF(JSON_VALID(latest_compliance.result), latest_compliance.result, JSON_OBJECT()),
                              '$.cfsArtifactRef'))), ''),
                          NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(
                              IF(JSON_VALID(latest_compliance.result), latest_compliance.result, JSON_OBJECT()),
                              '$.cfsArtifactId'))), '')
                      ) = latest_cfs.artifact_id
                     THEN (
                         SELECT file_metadata.generated_at
                           FROM JSON_TABLE(
                               IF(
                                   JSON_TYPE(JSON_EXTRACT(
                                       IF(JSON_VALID(latest_cfs.result), latest_cfs.result, JSON_OBJECT()),
                                       '$.files')) = 'ARRAY',
                                   JSON_EXTRACT(
                                       IF(JSON_VALID(latest_cfs.result), latest_cfs.result, JSON_OBJECT()),
                                       '$.files'),
                                   JSON_ARRAY()
                               ),
                               '$[*]' COLUMNS (
                                   ordinal_no FOR ORDINALITY,
                                   file_id VARCHAR(255) PATH '$.fileId'
                                       NULL ON EMPTY NULL ON ERROR,
                                   generated_at DATETIME(6) PATH '$.generatedAt'
                                       NULL ON EMPTY NULL ON ERROR
                               )
                           ) AS file_metadata
                          WHERE NULLIF(TRIM(file_metadata.file_id), '') IS NOT NULL
                            AND file_metadata.generated_at IS NOT NULL
                          ORDER BY file_metadata.ordinal_no ASC
                          LIMIT 1
                     )
                 END,
                 CAST(w.updated_at AS DATETIME(6))
             ) DESC,
             w.workflow_id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    // H2 has no JSON_TABLE/JSON_VALID support; this dialect keeps the existing
    // integration test focused on report-center filtering and pagination.
    @Select(value = """
            <script>
            SELECT w.workflow_id AS workflowId,
                   w.person_id AS customerId,
                   COALESCE(NULLIF(p.display_name, ''), p.full_name) AS customerName,
                   w.workflow_status AS workflowStatus,
                   w.template_id AS templateId,
                   w.as_of_date AS asOfDate,
                   w.error_code AS errorCode,
                   w.error_message AS errorMessage,
                   w.updated_at AS updatedAt
              FROM workflow_state w
              JOIN person p ON p.person_id = w.person_id
             WHERE w.created_by = #{userId}
               AND w.workflow_status IN ('WAITING_INPUT', 'WAITING_REVIEW', 'GENERATING_OUTPUT', 'COMPLETED', 'FAILED')
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact compliance
                    WHERE compliance.workflow_id = w.workflow_id
                      AND compliance.agent_type = 'COMPLIANCE_CHECK'
                      AND UPPER(compliance.compliance_result) IN ('PASS', 'REVIEW_REQUIRED')
                      AND compliance.version = (
                          SELECT MAX(latest_compliance.version)
                            FROM agent_artifact latest_compliance
                           WHERE latest_compliance.workflow_id = w.workflow_id
                             AND latest_compliance.agent_type = 'COMPLIANCE_CHECK'
                      )
               )
               AND EXISTS (
                   SELECT 1
                     FROM agent_artifact cfs
                    WHERE cfs.workflow_id = w.workflow_id
                      AND cfs.agent_type = 'SOLUTION_DESIGN'
               )
               AND EXISTS (
                   SELECT 1
                     FROM user_customer_scope ucs
                    WHERE ucs.user_id = #{userId}
                      AND ucs.person_id = w.person_id
                      AND ucs.scope_status = 1
               )
            <if test="customerId != null">
               AND w.person_id = #{customerId}
            </if>
            <if test="keyword != null and keyword != ''">
               AND (
                   w.workflow_id LIKE CONCAT('%', #{keyword}, '%')
                   OR w.template_id LIKE CONCAT('%', #{keyword}, '%')
                   OR COALESCE(NULLIF(p.display_name, ''), p.full_name) LIKE CONCAT('%', #{keyword}, '%')
               )
            </if>
             ORDER BY w.updated_at DESC, w.workflow_id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """, databaseId = "h2")
    List<CfsReportWorkflowRow> findForReportCenter(
            @Param("userId") String userId,
            @Param("customerId") Long customerId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);
}
