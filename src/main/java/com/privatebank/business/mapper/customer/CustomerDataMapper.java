package com.privatebank.business.mapper.customer;

import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.dto.customer.EvidenceResponse;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface CustomerDataMapper {

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM person
             WHERE person_id IN
             <foreach collection="personIds" item="personId" open="(" separator="," close=")">
                 #{personId}
             </foreach>
            </script>
            """)
    long countExistingCustomers(@Param("personIds") List<Long> personIds);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM person p
             WHERE 1 = 1
            <if test="allowedPersonIds != null">
              <choose>
                <when test="allowedPersonIds.size() == 0">AND 1 = 0</when>
                <otherwise>
                  AND p.person_id IN
                  <foreach collection="allowedPersonIds" item="personId" open="(" separator="," close=")">
                    #{personId}
                  </foreach>
                </otherwise>
              </choose>
            </if>
            <if test="keyword != null and keyword != ''">
              AND (p.full_name LIKE CONCAT('%', #{keyword}, '%')
                   OR p.display_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countCustomers(
            @Param("keyword") String keyword,
            @Param("allowedPersonIds") List<Long> allowedPersonIds);

    @Select("""
            <script>
            SELECT p.person_id AS personId,
                   p.full_name AS fullName,
                   p.display_name AS displayName,
                   p.person_type AS personType,
                   p.verification_status AS verificationStatus,
                   (SELECT rp.risk_level FROM risk_preference rp
                     WHERE rp.person_id = p.person_id
                     ORDER BY rp.created_at DESC, rp.risk_preference_id DESC LIMIT 1) AS riskLevel
              FROM person p
             WHERE 1 = 1
            <if test="allowedPersonIds != null">
              <choose>
                <when test="allowedPersonIds.size() == 0">AND 1 = 0</when>
                <otherwise>
                  AND p.person_id IN
                  <foreach collection="allowedPersonIds" item="personId" open="(" separator="," close=")">
                    #{personId}
                  </foreach>
                </otherwise>
              </choose>
            </if>
            <if test="keyword != null and keyword != ''">
              AND (p.full_name LIKE CONCAT('%', #{keyword}, '%')
                   OR p.display_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
             ORDER BY p.person_id
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<CustomerSummaryResponse> findCustomers(
            @Param("keyword") String keyword,
            @Param("allowedPersonIds") List<Long> allowedPersonIds,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT p.person_id AS personId,
                   p.full_name AS fullName,
                   p.display_name AS displayName,
                   p.person_type AS personType,
                   p.verification_status AS verificationStatus,
                   (SELECT rp.risk_level FROM risk_preference rp
                     WHERE rp.person_id = p.person_id
                     ORDER BY rp.created_at DESC, rp.risk_preference_id DESC LIMIT 1) AS riskLevel
              FROM person p
             WHERE p.person_id = #{personId}
            """)
    CustomerSummaryResponse findSummary(@Param("personId") Long personId);

    @Select("SELECT * FROM person_profile WHERE person_id = #{personId}")
    Map<String, Object> findProfile(@Param("personId") Long personId);

    @Select("SELECT * FROM person_career WHERE person_id = #{personId} "
            + "ORDER BY start_date DESC, career_id DESC LIMIT 201")
    List<Map<String, Object>> findCareers(@Param("personId") Long personId);

    @Select("SELECT * FROM risk_preference WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, risk_preference_id DESC LIMIT 201")
    List<Map<String, Object>> findRiskPreferences(@Param("personId") Long personId);

    @Select("SELECT * FROM financial_fact WHERE person_id = #{personId} "
            + "ORDER BY effective_date DESC, financial_fact_id DESC LIMIT 201")
    List<Map<String, Object>> findFinancialFacts(@Param("personId") Long personId);

    @Select("SELECT * FROM product_holding WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, product_holding_id DESC LIMIT 201")
    List<Map<String, Object>> findHoldings(@Param("personId") Long personId);

    @Select("SELECT * FROM financial_event WHERE person_id = #{personId} "
            + "ORDER BY event_date DESC, financial_event_id DESC LIMIT 201")
    List<Map<String, Object>> findEvents(@Param("personId") Long personId);

    @Select("SELECT * FROM service_record WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, service_record_id DESC LIMIT 201")
    List<Map<String, Object>> findServiceRecords(@Param("personId") Long personId);

    @Select("SELECT * FROM customer_interaction_note WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, interaction_note_id DESC LIMIT 201")
    List<Map<String, Object>> findInteractionNotes(@Param("personId") Long personId);

    @Select("""
            SELECT r.*, e.enterprise_name, e.stock_code, e.industry_name, e.headquarters
              FROM person_enterprise_relation r
              JOIN enterprise e ON e.enterprise_id = r.enterprise_id
             WHERE r.person_id = #{personId}
             ORDER BY r.is_core_relation DESC, r.created_at DESC, r.person_enterprise_relation_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findEnterpriseRelations(@Param("personId") Long personId);

    @Select("""
            SELECT b.*
              FROM enterprise_business b
             WHERE EXISTS (
                   SELECT 1
                     FROM person_enterprise_relation r
                    WHERE r.person_id = #{personId}
                      AND r.enterprise_id = b.enterprise_id
             )
             ORDER BY b.created_at DESC, b.enterprise_business_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findEnterpriseBusinesses(@Param("personId") Long personId);

    @Select("""
            SELECT m.*
              FROM enterprise_financial_metric m
             WHERE EXISTS (
                   SELECT 1
                     FROM person_enterprise_relation r
                    WHERE r.person_id = #{personId}
                      AND r.enterprise_id = m.enterprise_id
             )
             ORDER BY m.created_at DESC, m.enterprise_financial_metric_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findEnterpriseFinancialMetrics(@Param("personId") Long personId);

    @Select("""
            SELECT e.*
              FROM enterprise_event e
             WHERE EXISTS (
                   SELECT 1
                     FROM person_enterprise_relation r
                    WHERE r.person_id = #{personId}
                      AND r.enterprise_id = e.enterprise_id
             )
             ORDER BY e.event_date DESC, e.created_at DESC, e.enterprise_event_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findEnterpriseEvents(@Param("personId") Long personId);

    @Select("""
            SELECT m.*
              FROM enterprise_market_relation m
             WHERE EXISTS (
                   SELECT 1
                     FROM person_enterprise_relation r
                    WHERE r.person_id = #{personId}
                      AND r.enterprise_id = m.enterprise_id
             )
             ORDER BY m.created_at DESC, m.enterprise_market_relation_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findEnterpriseMarketRelations(@Param("personId") Long personId);

    @Select("SELECT * FROM family_member WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, family_member_id DESC LIMIT 201")
    List<Map<String, Object>> findFamilyMembers(@Param("personId") Long personId);

    @Select("""
            SELECT r.*, m.public_disclosure_level
              FROM person_family_relation r
              JOIN family_member m ON m.family_member_id = r.family_member_id
             WHERE r.person_id = #{personId}
             ORDER BY r.created_at DESC, r.person_family_relation_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findFamilyRelations(@Param("personId") Long personId);

    @Select("SELECT * FROM succession_arrangement WHERE person_id = #{personId} "
            + "ORDER BY created_at DESC, succession_arrangement_id DESC LIMIT 201")
    List<Map<String, Object>> findSuccessionArrangements(@Param("personId") Long personId);

    @Select("""
            SELECT r.*, o.organization_name, o.organization_type
              FROM person_social_relation r
              JOIN social_organization o ON o.social_organization_id = r.social_organization_id
             WHERE r.person_id = #{personId}
             ORDER BY r.created_at DESC, r.person_social_relation_id DESC
             LIMIT 201
            """)
    List<Map<String, Object>> findSocialRelations(@Param("personId") Long personId);

    @Select("SELECT * FROM social_activity WHERE person_id = #{personId} "
            + "ORDER BY activity_date DESC, social_activity_id DESC LIMIT 201")
    List<Map<String, Object>> findSocialActivities(@Param("personId") Long personId);

    @Select("SELECT * FROM public_reputation WHERE person_id = #{personId} "
            + "ORDER BY publication_date DESC, public_reputation_id DESC LIMIT 201")
    List<Map<String, Object>> findPublicReputation(@Param("personId") Long personId);

    @Select("SELECT * FROM reputation_risk WHERE person_id = #{personId} "
            + "ORDER BY event_date DESC, reputation_risk_id DESC LIMIT 201")
    List<Map<String, Object>> findReputationRisks(@Param("personId") Long personId);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM financial_fact WHERE person_id = #{personId})
                + (SELECT COUNT(*) FROM person_career WHERE person_id = #{personId}) AS person_count,
              (SELECT COUNT(*) FROM person_enterprise_relation WHERE person_id = #{personId}) AS enterprise_count,
              (SELECT COUNT(*) FROM family_member WHERE person_id = #{personId}) AS family_count,
              (SELECT COUNT(*) FROM person_social_relation WHERE person_id = #{personId})
                + (SELECT COUNT(*) FROM social_activity WHERE person_id = #{personId}) AS social_count
            """)
    Map<String, Object> findDimensionCounts(@Param("personId") Long personId);

    @Select("""
            SELECT source_id AS sourceRef,
                   file_name AS fileName,
                   sheet_name AS sheetName,
                   source_row_number AS sourceRowNumber,
                   column_name AS columnName,
                   cell_reference AS cellReference,
                   original_text AS originalText,
                   source_level AS sourceLevel,
                   source_date AS sourceDate,
                   source_locator AS sourceLocator
              FROM source_document
             WHERE source_id = #{sourceId}
            """)
    EvidenceResponse findEvidence(@Param("sourceId") Long sourceId);

    @Select("""
            SELECT DISTINCT person_id FROM (
                SELECT person_id FROM person_profile WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM person_career WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM risk_preference WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM financial_fact WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM product_holding WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM financial_event WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM service_record WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM customer_interaction_note WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM family_member WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM succession_arrangement WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM person_social_relation WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM social_activity WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM public_reputation WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM reputation_risk WHERE source_id = #{sourceId}
                UNION ALL SELECT person_id FROM document WHERE source_id = #{sourceId} AND person_id IS NOT NULL
            ) evidence_owner
            """)
    List<Long> findEvidencePersonIds(@Param("sourceId") Long sourceId);

    @Select("""
            SELECT 'risk_preference' AS source_table, risk_preference_id AS item_id,
                   verification_status, source_id
              FROM risk_preference WHERE person_id = #{personId} AND verification_status != 'VERIFIED'
            UNION ALL
            SELECT 'financial_fact', financial_fact_id, verification_status, source_id
              FROM financial_fact WHERE person_id = #{personId} AND verification_status != 'VERIFIED'
            UNION ALL
            SELECT 'customer_interaction_note', interaction_note_id, verification_status, source_id
              FROM customer_interaction_note WHERE person_id = #{personId} AND verification_status != 'VERIFIED'
            UNION ALL
            SELECT 'customer_personalized_fact', fact_id, verification_status, source_id
              FROM customer_personalized_fact
             WHERE person_id = #{personId} AND verification_status NOT IN ('CONFIRMED')
            LIMIT 100
            """)
    List<Map<String, Object>> findUnresolved(@Param("personId") Long personId);
}
