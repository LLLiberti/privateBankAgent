package com.privatebank.customer.repository;

import com.privatebank.customer.api.CustomerSummaryResponse;
import com.privatebank.customer.api.EvidenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CustomerDataRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public long countCustomers(String keyword, List<Long> allowedPersonIds) {
        QueryParts parts = customerWhere(keyword, allowedPersonIds);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM person p " + parts.sql(), parts.parameters(), Long.class);
        return count == null ? 0 : count;
    }

    public List<CustomerSummaryResponse> findCustomers(
            String keyword, List<Long> allowedPersonIds, int offset, int pageSize) {
        QueryParts parts = customerWhere(keyword, allowedPersonIds);
        MapSqlParameterSource parameters = parts.parameters()
                .addValue("offset", offset)
                .addValue("pageSize", pageSize);
        String sql = """
                SELECT p.person_id, p.full_name, p.display_name, p.person_type, p.verification_status,
                       (SELECT rp.risk_level FROM risk_preference rp
                         WHERE rp.person_id = p.person_id ORDER BY rp.created_at DESC LIMIT 1) AS risk_level
                  FROM person p
                """ + parts.sql() + " ORDER BY p.person_id LIMIT :pageSize OFFSET :offset";
        return jdbc.query(sql, parameters, (rs, rowNum) -> new CustomerSummaryResponse(
                rs.getLong("person_id"),
                rs.getString("full_name"),
                rs.getString("display_name"),
                rs.getString("person_type"),
                rs.getString("verification_status"),
                rs.getString("risk_level")));
    }

    public Optional<CustomerSummaryResponse> findSummary(Long personId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT p.person_id, p.full_name, p.display_name, p.person_type, p.verification_status,
                           (SELECT rp.risk_level FROM risk_preference rp
                             WHERE rp.person_id = p.person_id ORDER BY rp.created_at DESC LIMIT 1) AS risk_level
                      FROM person p WHERE p.person_id = :personId
                    """, Map.of("personId", personId), (rs, rowNum) -> new CustomerSummaryResponse(
                    rs.getLong("person_id"), rs.getString("full_name"), rs.getString("display_name"),
                    rs.getString("person_type"), rs.getString("verification_status"), rs.getString("risk_level"))));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Map<String, Object> findOne(String sql, Long personId) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("personId", personId));
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.getFirst());
    }

    public List<Map<String, Object>> findMany(String sql, Long personId) {
        return jdbc.queryForList(sql, Map.of("personId", personId)).stream()
                .map(LinkedHashMap::new)
                .map(map -> (Map<String, Object>) map)
                .toList();
    }

    public long count(String table, Long personId) {
        if (!ALLOWED_PERSON_TABLES.contains(table)) {
            throw new IllegalArgumentException("Unsupported customer table: " + table);
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE person_id = :personId",
                Map.of("personId", personId), Long.class);
        return count == null ? 0 : count;
    }

    public Optional<EvidenceResponse> findEvidence(Long sourceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT source_id, file_name, sheet_name, source_row_number, column_name,
                           cell_reference, original_text, source_level, source_date, source_locator
                      FROM source_document WHERE source_id = :sourceId
                    """, Map.of("sourceId", sourceId), (rs, rowNum) -> {
                Date sourceDate = rs.getDate("source_date");
                return new EvidenceResponse(
                        rs.getLong("source_id"), rs.getString("file_name"), rs.getString("sheet_name"),
                        rs.getObject("source_row_number", Integer.class), rs.getString("column_name"),
                        rs.getString("cell_reference"), rs.getString("original_text"),
                        rs.getString("source_level"), sourceDate == null ? null : sourceDate.toLocalDate(),
                        rs.getString("source_locator"));
            }));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public List<Long> findEvidencePersonIds(Long sourceId) {
        String sql = """
                SELECT DISTINCT person_id FROM (
                    SELECT person_id FROM person_profile WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM person_career WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM risk_preference WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM financial_fact WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM product_holding WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM financial_event WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM service_record WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM customer_interaction_note WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM family_member WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM succession_arrangement WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM person_social_relation WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM social_activity WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM public_reputation WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM reputation_risk WHERE source_id = :sourceId
                    UNION ALL SELECT person_id FROM document WHERE source_id = :sourceId AND person_id IS NOT NULL
                ) evidence_owner
                """;
        return jdbc.queryForList(sql, Map.of("sourceId", sourceId), Long.class);
    }

    public List<Map<String, Object>> findUnresolved(Long personId) {
        String sql = """
                SELECT 'risk_preference' AS source_table, risk_preference_id AS item_id,
                       verification_status, source_id
                  FROM risk_preference WHERE person_id = :personId AND verification_status <> 'VERIFIED'
                UNION ALL
                SELECT 'financial_fact', financial_fact_id, verification_status, source_id
                  FROM financial_fact WHERE person_id = :personId AND verification_status <> 'VERIFIED'
                UNION ALL
                SELECT 'customer_interaction_note', interaction_note_id, verification_status, source_id
                  FROM customer_interaction_note WHERE person_id = :personId AND verification_status <> 'VERIFIED'
                UNION ALL
                SELECT 'customer_personalized_fact', fact_id, verification_status, source_id
                  FROM customer_personalized_fact WHERE person_id = :personId AND verification_status NOT IN ('CONFIRMED')
                LIMIT 100
                """;
        return findMany(sql, personId);
    }

    private QueryParts customerWhere(String keyword, List<Long> allowedPersonIds) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (allowedPersonIds != null) {
            if (allowedPersonIds.isEmpty()) {
                clauses.add("1 = 0");
            } else {
                clauses.add("p.person_id IN (:personIds)");
                parameters.addValue("personIds", allowedPersonIds);
            }
        }
        if (StringUtils.hasText(keyword)) {
            clauses.add("(p.full_name LIKE :keyword OR p.display_name LIKE :keyword)");
            parameters.addValue("keyword", "%" + keyword.trim() + "%");
        }
        return new QueryParts(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), parameters);
    }

    private record QueryParts(String sql, MapSqlParameterSource parameters) {
    }

    private static final List<String> ALLOWED_PERSON_TABLES = List.of(
            "person_career", "risk_preference", "financial_fact", "product_holding", "financial_event",
            "service_record", "customer_interaction_note", "person_enterprise_relation", "family_member",
            "succession_arrangement", "person_social_relation", "social_activity", "public_reputation",
            "reputation_risk", "customer_personalized_fact", "document");
}
