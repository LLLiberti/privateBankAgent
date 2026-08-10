-- 四维企业家受控演示样例数据 - MySQL 8.0 物理模型草案
-- 本脚本仅建表，不导入 Excel 数据。执行前请在目标数据库中设置 utf8mb4。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS import_batch (
  import_batch_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  batch_name VARCHAR(128) NOT NULL,
  source_description VARCHAR(500) NOT NULL,
  imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  import_status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
  operator_name VARCHAR(128) NULL,
  record_count INT UNSIGNED NOT NULL DEFAULT 0,
  note TEXT NULL,
  UNIQUE KEY uk_import_batch_name (batch_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS source_document (
  source_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  import_batch_id BIGINT UNSIGNED NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  sheet_name VARCHAR(128) NOT NULL,
  source_row_number INT UNSIGNED NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  cell_reference VARCHAR(32) NOT NULL,
  original_text TEXT NULL,
  source_level VARCHAR(2) NOT NULL DEFAULT 'S0',
  source_date DATE NULL,
  source_locator VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_source_document_batch FOREIGN KEY (import_batch_id) REFERENCES import_batch(import_batch_id),
  UNIQUE KEY uk_source_cell (import_batch_id, file_name, sheet_name, cell_reference),
  KEY idx_source_row (import_batch_id, file_name, sheet_name, source_row_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 导入暂存层：原始 Excel 一行一条记录，raw_cells 保存原列名和单元格内容的 JSON。
CREATE TABLE IF NOT EXISTS stg_import_row (
  stg_row_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  import_batch_id BIGINT UNSIGNED NOT NULL,
  data_dimension VARCHAR(16) NOT NULL,
  source_sequence INT UNSIGNED NULL,
  person_name VARCHAR(128) NOT NULL,
  core_enterprise_name VARCHAR(255) NOT NULL,
  source_file_name VARCHAR(255) NOT NULL,
  sheet_name VARCHAR(128) NOT NULL,
  source_row_number INT UNSIGNED NOT NULL,
  raw_cells JSON NOT NULL,
  parse_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  parse_message TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_stg_import_batch FOREIGN KEY (import_batch_id) REFERENCES import_batch(import_batch_id),
  UNIQUE KEY uk_stg_row (import_batch_id, data_dimension, source_file_name, sheet_name, source_row_number),
  KEY idx_stg_dimension_status (data_dimension, parse_status),
  KEY idx_stg_person_enterprise (person_name, core_enterprise_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person (
  person_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  full_name VARCHAR(128) NOT NULL,
  normalized_name VARCHAR(128) NOT NULL,
  person_type VARCHAR(24) NOT NULL DEFAULT 'ENTREPRENEUR',
  display_name VARCHAR(128) NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_person_normalized_name (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS enterprise (
  enterprise_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  enterprise_name VARCHAR(255) NOT NULL,
  normalized_name VARCHAR(255) NOT NULL,
  stock_code VARCHAR(32) NULL,
  registration_date DATE NULL,
  registration_place VARCHAR(255) NULL,
  listing_date DATE NULL,
  headquarters VARCHAR(255) NULL,
  employee_count INT UNSIGNED NULL,
  industry_name VARCHAR(255) NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_enterprise_normalized_name (normalized_name),
  KEY idx_enterprise_stock_code (stock_code),
  KEY idx_enterprise_industry (industry_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person_profile (
  person_id BIGINT UNSIGNED PRIMARY KEY,
  gender VARCHAR(16) NULL,
  birth_date DATE NULL,
  birth_year SMALLINT UNSIGNED NULL,
  native_place VARCHAR(255) NULL,
  birth_place VARCHAR(255) NULL,
  marital_status VARCHAR(32) NULL,
  education_level VARCHAR(64) NULL,
  school_name VARCHAR(255) NULL,
  residence VARCHAR(500) NULL,
  health_summary VARCHAR(500) NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_profile_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_profile_source FOREIGN KEY (source_id) REFERENCES source_document(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person_enterprise_relation (
  person_enterprise_relation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  enterprise_id BIGINT UNSIGNED NOT NULL,
  relation_type VARCHAR(48) NOT NULL,
  title VARCHAR(255) NULL,
  ownership_percentage DECIMAL(9,4) NULL,
  voting_right_percentage DECIMAL(9,4) NULL,
  is_core_relation BOOLEAN NOT NULL DEFAULT FALSE,
  valid_from DATE NULL,
  valid_to DATE NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  source_level VARCHAR(2) NOT NULL DEFAULT 'S0',
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  raw_text TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_per_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_per_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_per_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_per_person (person_id, relation_type),
  KEY idx_per_enterprise (enterprise_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person_career (
  career_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  organization_name VARCHAR(255) NOT NULL,
  position_title VARCHAR(255) NULL,
  start_date DATE NULL,
  end_date DATE NULL,
  career_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_career_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_career_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_career_person (person_id, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS financial_fact (
  financial_fact_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  fact_category VARCHAR(48) NOT NULL,
  asset_type VARCHAR(48) NULL,
  amount DECIMAL(20,2) NULL,
  currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
  percentage DECIMAL(9,4) NULL,
  estimate_flag BOOLEAN NOT NULL DEFAULT FALSE,
  effective_date DATE NULL,
  description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ff_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_ff_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_ff_person_category (person_id, fact_category),
  KEY idx_ff_effective_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS product_holding (
  product_holding_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  product_type VARCHAR(64) NOT NULL,
  product_name VARCHAR(255) NULL,
  amount DECIMAL(20,2) NULL,
  currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
  maturity_date DATE NULL,
  risk_level VARCHAR(16) NULL,
  liquidity_note VARCHAR(500) NULL,
  holding_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_holding_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_holding_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_holding_person_maturity (person_id, maturity_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS risk_preference (
  risk_preference_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  risk_level VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  actual_preference VARCHAR(128) NULL,
  max_drawdown DECIMAL(9,4) NULL,
  investment_horizon VARCHAR(128) NULL,
  liquidity_requirement VARCHAR(128) NULL,
  preference_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_risk_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_risk_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_risk_person (person_id, risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS financial_event (
  financial_event_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  event_date DATE NULL,
  amount DECIMAL(20,2) NULL,
  currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
  purpose VARCHAR(500) NULL,
  event_description TEXT NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_fin_event_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_fin_event_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_fin_event_person_date (person_id, event_date),
  KEY idx_fin_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS service_record (
  service_record_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  service_type VARCHAR(64) NOT NULL,
  service_years DECIMAL(5,2) NULL,
  service_frequency VARCHAR(128) NULL,
  service_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_service_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_service_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_service_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS customer_interaction_note (
  interaction_note_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  note_type VARCHAR(48) NOT NULL,
  note_text TEXT NOT NULL,
  is_explicit_expression BOOLEAN NOT NULL DEFAULT FALSE,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_note_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_note_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_note_person_type (person_id, note_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS enterprise_business (
  enterprise_business_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  enterprise_id BIGINT UNSIGNED NOT NULL,
  business_line VARCHAR(255) NOT NULL,
  business_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_eb_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_eb_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_eb_enterprise (enterprise_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS enterprise_financial_metric (
  enterprise_financial_metric_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  enterprise_id BIGINT UNSIGNED NOT NULL,
  reporting_period VARCHAR(32) NOT NULL,
  metric_name VARCHAR(64) NOT NULL,
  metric_value DECIMAL(20,2) NOT NULL,
  unit_name VARCHAR(32) NOT NULL DEFAULT 'CNY_100M',
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_efm_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_efm_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  UNIQUE KEY uk_efm_period_metric (enterprise_id, reporting_period, metric_name),
  KEY idx_efm_metric (metric_name, reporting_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS enterprise_event (
  enterprise_event_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  enterprise_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  event_date DATE NULL,
  risk_level VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
  event_description TEXT NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ee_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_ee_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_ee_enterprise_date (enterprise_id, event_date),
  KEY idx_ee_type_risk (event_type, risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS enterprise_market_relation (
  enterprise_market_relation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  enterprise_id BIGINT UNSIGNED NOT NULL,
  counterpart_name VARCHAR(255) NOT NULL,
  relation_type VARCHAR(48) NOT NULL,
  relation_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emr_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_emr_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_emr_enterprise_type (enterprise_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS family_member (
  family_member_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  member_name VARCHAR(128) NULL,
  protected_alias VARCHAR(128) NULL,
  public_disclosure_level VARCHAR(24) NOT NULL DEFAULT 'RESTRICTED',
  member_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_family_member_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_family_member_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_family_member_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person_family_relation (
  person_family_relation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  family_member_id BIGINT UNSIGNED NOT NULL,
  relation_type VARCHAR(48) NOT NULL,
  relation_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pfr_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_pfr_member FOREIGN KEY (family_member_id) REFERENCES family_member(family_member_id),
  CONSTRAINT fk_pfr_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_pfr_person_relation (person_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS succession_arrangement (
  succession_arrangement_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  enterprise_id BIGINT UNSIGNED NULL,
  arrangement_status VARCHAR(48) NOT NULL,
  candidate_description VARCHAR(500) NULL,
  governance_model VARCHAR(255) NULL,
  arrangement_description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_success_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_success_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(enterprise_id),
  CONSTRAINT fk_success_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_success_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS social_organization (
  social_organization_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  organization_name VARCHAR(255) NOT NULL,
  organization_type VARCHAR(48) NOT NULL,
  normalized_name VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_social_org_normalized_name (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS person_social_relation (
  person_social_relation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  social_organization_id BIGINT UNSIGNED NOT NULL,
  relation_type VARCHAR(48) NOT NULL,
  role_title VARCHAR(255) NULL,
  valid_from DATE NULL,
  valid_to DATE NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  raw_text TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_psr_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_psr_org FOREIGN KEY (social_organization_id) REFERENCES social_organization(social_organization_id),
  CONSTRAINT fk_psr_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_psr_person_type (person_id, relation_type),
  KEY idx_psr_org (social_organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS social_activity (
  social_activity_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  activity_type VARCHAR(48) NOT NULL,
  activity_name VARCHAR(255) NULL,
  partner_name VARCHAR(255) NULL,
  activity_date DATE NULL,
  amount DECIMAL(20,2) NULL,
  currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
  activity_description TEXT NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sa_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_sa_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_sa_person_type (person_id, activity_type),
  KEY idx_sa_date (activity_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS public_reputation (
  public_reputation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  reputation_type VARCHAR(48) NOT NULL,
  title VARCHAR(500) NOT NULL,
  publisher_name VARCHAR(255) NULL,
  publication_date DATE NULL,
  description TEXT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pr_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_pr_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_pr_person_type (person_id, reputation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reputation_risk (
  reputation_risk_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  person_id BIGINT UNSIGNED NOT NULL,
  risk_topic VARCHAR(255) NOT NULL,
  risk_level VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
  event_date DATE NULL,
  risk_description TEXT NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  verification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_rr_person FOREIGN KEY (person_id) REFERENCES person(person_id),
  CONSTRAINT fk_rr_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_rr_person_level (person_id, risk_level),
  KEY idx_rr_status (verification_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS data_quality_issue (
  data_quality_issue_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  import_batch_id BIGINT UNSIGNED NOT NULL,
  stg_row_id BIGINT UNSIGNED NULL,
  source_id BIGINT UNSIGNED NULL,
  issue_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  issue_message TEXT NOT NULL,
  issue_status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  resolved_by VARCHAR(128) NULL,
  resolved_at DATETIME NULL,
  resolution_note TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dqi_batch FOREIGN KEY (import_batch_id) REFERENCES import_batch(import_batch_id),
  CONSTRAINT fk_dqi_stg FOREIGN KEY (stg_row_id) REFERENCES stg_import_row(stg_row_id),
  CONSTRAINT fk_dqi_source FOREIGN KEY (source_id) REFERENCES source_document(source_id),
  KEY idx_dqi_status_severity (issue_status, severity),
  KEY idx_dqi_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
