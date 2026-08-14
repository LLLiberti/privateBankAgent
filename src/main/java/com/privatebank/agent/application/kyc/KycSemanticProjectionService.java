package com.privatebank.agent.application.kyc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts local free text to a small, documented code vocabulary.  It never
 * returns a substring of the source text, so it is safe to use before a remote
 * model invocation.  Unknown text deliberately produces no code.
 */
public final class KycSemanticProjectionService {

    private static final Map<String, List<Rule>> RULES = rules();

    public List<String> interactionTopics(Object... text) {
        return project("INTERACTION", text);
    }

    public List<String> businessCategories(Object... text) {
        return project("BUSINESS", text);
    }

    public List<String> enterpriseEventSignals(Object... text) {
        return project("ENTERPRISE_EVENT", text);
    }

    public List<String> activitySignals(Object... text) {
        return project("ACTIVITY", text);
    }

    public List<String> reputationSignals(Object... text) {
        return project("REPUTATION", text);
    }

    public List<String> reputationRiskCategories(Object... text) {
        return project("REPUTATION_RISK", text);
    }

    public String roleCategory(Object... text) {
        return first("ROLE", text);
    }

    public String industryCategory(Object... text) {
        return first("INDUSTRY", text);
    }

    public String assetCategory(Object... text) {
        return first("ASSET", text);
    }

    public String governanceCategory(Object... text) {
        return first("GOVERNANCE", text);
    }

    public Set<String> managerSupplementSignals(String... text) {
        List<String> signals = project("MANAGER_SUPPLEMENT", (Object[]) text);
        return signals.isEmpty() ? Set.of("SUPPLEMENT_PROVIDED") : Set.copyOf(signals);
    }

    private List<String> project(String group, Object... source) {
        String text = join(source);
        if (text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Rule rule : RULES.getOrDefault(group, List.of())) {
            if (rule.matches(text) && !result.contains(rule.code())) {
                result.add(rule.code());
            }
        }
        return List.copyOf(result);
    }

    private String first(String group, Object... source) {
        return project(group, source).stream().findFirst().orElse(null);
    }

    private String join(Object... source) {
        StringBuilder combined = new StringBuilder();
        for (Object item : source) {
            if (item instanceof String text && !text.isBlank()) {
                combined.append(' ').append(text.toLowerCase(Locale.ROOT));
            }
        }
        return combined.toString();
    }

    private static Map<String, List<Rule>> rules() {
        Map<String, List<Rule>> rules = new LinkedHashMap<>();
        rules.put("INTERACTION", List.of(
                rule("LONG_TERM_PLANNING", "长期", "长期规划", "long term"),
                rule("DIGITAL_TECHNOLOGY", "人工智能", "ai", "科技", "数字化", "互联网"),
                rule("WEALTH_MANAGEMENT", "财富", "资产配置", "投资"),
                rule("PHILANTHROPY", "公益", "慈善", "捐赠"),
                rule("CROSS_BORDER", "跨境", "海外", "国际")));
        rules.put("BUSINESS", List.of(
                rule("DIGITAL_PLATFORM", "社交", "平台", "互联网", "online platform"),
                rule("DIGITAL_CONTENT", "游戏", "内容", "娱乐", "music", "video"),
                rule("FINTECH_ENTERPRISE_SERVICE", "金融科技", "支付", "企业服务", "fintech"),
                rule("CLOUD_COMPUTING", "云计算", "云服务", "云平台", "云业务", "上云", "cloud"),
                rule("ARTIFICIAL_INTELLIGENCE", "人工智能", "ai", "大模型"),
                rule("MANUFACTURING", "制造", "工业", "自动化", "manufacturing")));
        rules.put("ENTERPRISE_EVENT", List.of(
                rule("SHARE_REPURCHASE", "回购", "repurchase", "buyback"),
                rule("AI_STRATEGY", "人工智能", "ai", "大模型"),
                rule("REGULATORY_ACTION", "监管", "处罚", "罚款", "合规", "antitrust"),
                rule("OPERATING_LICENSE", "牌照", "许可", "license"),
                rule("PROFIT_GROWTH", "利润增长", "业绩增长", "增长", "profit growth"),
                rule("INDUSTRY_TRANSFORMATION", "转型", "重组", "transform")));
        rules.put("ACTIVITY", List.of(
                rule("PHILANTHROPY", "公益", "慈善", "捐赠", "基金会"),
                rule("EDUCATION_SUPPORT", "教育", "学校", "奖学金"),
                rule("RESEARCH_SUPPORT", "研究", "科研", "实验室"),
                rule("ESG_SUSTAINABILITY", "esg", "可持续", "环保", "低碳")));
        rules.put("REPUTATION", List.of(
                rule("GLOBAL_INFLUENCE", "全球", "国际", "world", "global"),
                rule("BUSINESS_LEADERSHIP", "企业家", "领袖", "leader"),
                rule("WEALTH_RANKING", "富豪", "财富榜", "ranking", "rich list"),
                rule("TECHNOLOGY_LEADERSHIP", "科技", "人工智能", "数字", "technology")));
        rules.put("REPUTATION_RISK", List.of(
                rule("ANTITRUST", "反垄断", "垄断", "antitrust"),
                rule("DATA_SECURITY", "数据安全", "隐私", "个人信息", "data security"),
                rule("MINOR_PROTECTION", "未成年", "青少年", "minor"),
                rule("CONTENT_GOVERNANCE", "内容", "算法", "推荐", "content"),
                rule("LABOR_PRACTICE", "劳动", "员工", "劳工", "labor"),
                rule("INVESTMENT_EXPANSION", "投资", "并购", "扩张", "acquisition")));
        rules.put("ROLE", List.of(
                rule("BOARD_GOVERNANCE", "董事", "board"),
                rule("EXECUTIVE_LEADERSHIP", "ceo", "总裁", "主席", "创始人", "executive"),
                rule("FINANCIAL_MANAGEMENT", "财务", "cfo", "finance"),
                rule("ADVISORY", "顾问", "advisor")));
        rules.put("INDUSTRY", List.of(
                rule("INTERNET_TECHNOLOGY", "互联网", "软件", "科技", "digital"),
                rule("FINANCIAL_SERVICES", "金融", "银行", "保险", "finance"),
                rule("MANUFACTURING", "制造", "工业", "manufacturing"),
                rule("REAL_ESTATE", "房地产", "地产", "real estate"),
                rule("HEALTHCARE", "医疗", "医药", "health")));
        rules.put("ASSET", List.of(
                rule("PUBLIC_EQUITY", "股票", "股权", "equity"),
                rule("FIXED_INCOME", "债券", "固收", "bond"),
                rule("FUND", "基金", "fund"),
                rule("INSURANCE", "保险", "insurance"),
                rule("PRIVATE_MARKET", "私募", "private"),
                rule("CASH", "现金", "存款", "cash")));
        rules.put("GOVERNANCE", List.of(
                rule("FAMILY_GOVERNANCE", "家族", "family"),
                rule("TRUST_STRUCTURE", "信托", "trust"),
                rule("PROFESSIONAL_MANAGEMENT", "职业经理", "professional management"),
                rule("BOARD_GOVERNANCE", "董事会", "board")));
        rules.put("MANAGER_SUPPLEMENT", List.of(
                rule("LIQUIDITY_NEED", "流动性", "liquidity", "现金"),
                rule("RISK_TOLERANCE", "风险承受", "risk tolerance", "回撤"),
                rule("ASSET_ALLOCATION", "资产配置", "asset allocation", "投资"),
                rule("FAMILY_SUCCESSION", "家族", "传承", "succession"),
                rule("ENTERPRISE_OWNERSHIP", "股权", "控制", "ownership"),
                rule("CROSS_BORDER_NEED", "跨境", "海外", "cross border"),
                rule("TAX_PLANNING", "税务", "tax"),
                rule("CHARITABLE_GIVING", "公益", "慈善", "charity"),
                rule("SERVICE_PREFERENCE", "服务", "service preference")));
        return Map.copyOf(rules);
    }

    private static Rule rule(String code, String... keywords) {
        return new Rule(code, List.of(keywords));
    }

    private record Rule(String code, List<String> keywords) {
        private boolean matches(String text) {
            return keywords.stream().anyMatch(keyword -> matchesKeyword(text, keyword));
        }

        private boolean matchesKeyword(String text, String keyword) {
            boolean asciiPhrase = keyword.codePoints().allMatch(character -> character < 128);
            if (!asciiPhrase) {
                return text.contains(keyword);
            }
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword)
                            + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text)
                    .find();
        }
    }
}
