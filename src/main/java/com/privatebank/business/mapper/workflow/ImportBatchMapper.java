package com.privatebank.business.mapper.workflow;

import com.privatebank.business.dto.workflow.AvailableImportBatchResponse;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ImportBatchMapper {

    @Select("""
            SELECT COUNT(DISTINCT ib.import_batch_id)
              FROM import_batch ib
              JOIN source_document sd ON sd.import_batch_id = ib.import_batch_id
              JOIN (
                  SELECT source_id, person_id FROM person_profile
                  UNION ALL SELECT source_id, person_id FROM person_career
                  UNION ALL SELECT source_id, person_id FROM risk_preference
                  UNION ALL SELECT source_id, person_id FROM financial_fact
                  UNION ALL SELECT source_id, person_id FROM product_holding
                  UNION ALL SELECT source_id, person_id FROM financial_event
                  UNION ALL SELECT source_id, person_id FROM service_record
                  UNION ALL SELECT source_id, person_id FROM customer_interaction_note
                  UNION ALL SELECT source_id, person_id FROM family_member
                  UNION ALL SELECT source_id, person_id FROM succession_arrangement
                  UNION ALL SELECT source_id, person_id FROM person_social_relation
                  UNION ALL SELECT source_id, person_id FROM social_activity
                  UNION ALL SELECT source_id, person_id FROM public_reputation
                  UNION ALL SELECT source_id, person_id FROM reputation_risk
                  UNION ALL SELECT source_id, person_id FROM document WHERE person_id IS NOT NULL
              ) customer_source ON customer_source.source_id = sd.source_id
             WHERE ib.import_status = 'COMPLETED'
               AND customer_source.person_id = #{personId}
            """)
    long countAvailableForCustomer(@Param("personId") Long personId);

    @Select("""
            SELECT DISTINCT ib.import_batch_id AS importBatchId,
                   ib.batch_name AS batchName,
                   ib.imported_at AS importedAt,
                   ib.record_count AS recordCount
              FROM import_batch ib
              JOIN source_document sd ON sd.import_batch_id = ib.import_batch_id
              JOIN (
                  SELECT source_id, person_id FROM person_profile
                  UNION ALL SELECT source_id, person_id FROM person_career
                  UNION ALL SELECT source_id, person_id FROM risk_preference
                  UNION ALL SELECT source_id, person_id FROM financial_fact
                  UNION ALL SELECT source_id, person_id FROM product_holding
                  UNION ALL SELECT source_id, person_id FROM financial_event
                  UNION ALL SELECT source_id, person_id FROM service_record
                  UNION ALL SELECT source_id, person_id FROM customer_interaction_note
                  UNION ALL SELECT source_id, person_id FROM family_member
                  UNION ALL SELECT source_id, person_id FROM succession_arrangement
                  UNION ALL SELECT source_id, person_id FROM person_social_relation
                  UNION ALL SELECT source_id, person_id FROM social_activity
                  UNION ALL SELECT source_id, person_id FROM public_reputation
                  UNION ALL SELECT source_id, person_id FROM reputation_risk
                  UNION ALL SELECT source_id, person_id FROM document WHERE person_id IS NOT NULL
              ) customer_source ON customer_source.source_id = sd.source_id
             WHERE ib.import_status = 'COMPLETED'
               AND customer_source.person_id = #{personId}
             ORDER BY ib.imported_at DESC, ib.import_batch_id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<AvailableImportBatchResponse> findAvailableForCustomer(
            @Param("personId") Long personId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                  FROM import_batch ib
                  JOIN source_document sd ON sd.import_batch_id = ib.import_batch_id
                  JOIN (
                      SELECT source_id, person_id FROM person_profile
                      UNION ALL SELECT source_id, person_id FROM person_career
                      UNION ALL SELECT source_id, person_id FROM risk_preference
                      UNION ALL SELECT source_id, person_id FROM financial_fact
                      UNION ALL SELECT source_id, person_id FROM product_holding
                      UNION ALL SELECT source_id, person_id FROM financial_event
                      UNION ALL SELECT source_id, person_id FROM service_record
                      UNION ALL SELECT source_id, person_id FROM customer_interaction_note
                      UNION ALL SELECT source_id, person_id FROM family_member
                      UNION ALL SELECT source_id, person_id FROM succession_arrangement
                      UNION ALL SELECT source_id, person_id FROM person_social_relation
                      UNION ALL SELECT source_id, person_id FROM social_activity
                      UNION ALL SELECT source_id, person_id FROM public_reputation
                      UNION ALL SELECT source_id, person_id FROM reputation_risk
                      UNION ALL SELECT source_id, person_id FROM document WHERE person_id IS NOT NULL
                  ) customer_source ON customer_source.source_id = sd.source_id
                 WHERE ib.import_batch_id = #{importBatchId}
                   AND ib.import_status = 'COMPLETED'
                   AND customer_source.person_id = #{personId}
            )
            """)
    boolean isCompletedAndAvailableForCustomer(
            @Param("importBatchId") Long importBatchId,
            @Param("personId") Long personId);
}
