package com.privatebank.business.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
