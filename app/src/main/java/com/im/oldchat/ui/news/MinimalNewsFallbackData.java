package com.im.oldchat.ui.news;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class MinimalNewsFallbackData {
    private static final String HOT_JSON =
            "{\"items\":["
                    + "{\"title\":\"多地消费政策加码，家电与汽车迎来新一轮以旧换新\","
                    + "\"summary\":\"综合人民日报与新华社公开报道，地方促消费政策继续落地。\","
                    + "\"url\":\"https://www.people.com.cn/\",\"source\":\"人民日报\",\"time\":\"今日\"},"
                    + "{\"title\":\"文旅市场热度提升，周末跨城出行需求增长\","
                    + "\"summary\":\"综合文旅公开数据，短途出游与夜经济持续活跃。\","
                    + "\"url\":\"https://www.xinhuanet.com/\",\"source\":\"新华网\",\"time\":\"今日\"},"
                    + "{\"title\":\"全国多地推进城市更新与老旧小区改造\","
                    + "\"summary\":\"城市更新项目加速推进，公共服务配套持续完善。\","
                    + "\"url\":\"https://www.gov.cn/\",\"source\":\"中国政府网\",\"time\":\"今日\"}"
                    + "]}";

    private static final String TECH_JSON =
            "{\"items\":["
                    + "{\"title\":\"国产 AI 终端生态持续扩展，端侧能力成为竞争焦点\","
                    + "\"summary\":\"来自大陆科技媒体公开消息，端云协同方案正在加速落地。\","
                    + "\"url\":\"https://www.ithome.com/\",\"source\":\"IT之家\",\"time\":\"今日\"},"
                    + "{\"title\":\"多家厂商发布新一代折叠屏方案，轻薄化继续推进\","
                    + "\"summary\":\"供应链与终端厂商在铰链、材料与功耗优化上持续投入。\","
                    + "\"url\":\"https://www.cnbeta.com.tw/\",\"source\":\"cnBeta\",\"time\":\"今日\"},"
                    + "{\"title\":\"开源社区发布多项工具链更新，开发效率再提升\","
                    + "\"summary\":\"编译、测试与发布流程持续自动化，企业落地节奏加快。\","
                    + "\"url\":\"https://www.oschina.net/\",\"source\":\"开源中国\",\"time\":\"今日\"}"
                    + "]}";

    private static final String FINANCE_JSON =
            "{\"items\":["
                    + "{\"title\":\"多家机构关注稳增长政策节奏，市场风险偏好回升\","
                    + "\"summary\":\"公开研报指出，内需修复与产业投资仍是后续重点。\","
                    + "\"url\":\"https://www.cls.cn/\",\"source\":\"财联社\",\"time\":\"今日\"},"
                    + "{\"title\":\"商品价格波动加剧，企业套保需求持续增长\","
                    + "\"summary\":\"多类大宗商品价格区间震荡，产业端风险管理意愿上升。\","
                    + "\"url\":\"https://www.stcn.com/\",\"source\":\"证券时报\",\"time\":\"今日\"},"
                    + "{\"title\":\"银行理财与债券产品稳步扩容，配置需求回暖\","
                    + "\"summary\":\"低波动资产配置需求提升，资金更重视流动性与安全性。\","
                    + "\"url\":\"https://www.cs.com.cn/\",\"source\":\"中证网\",\"time\":\"今日\"}"
                    + "]}";

    private static final String BLOCK_JSON =
            "{\"items\":["
                    + "{\"title\":\"主流交易平台资金流向出现分化，波动率抬升\","
                    + "\"summary\":\"综合大陆区块资讯公开快讯整理，短线波动需关注仓位控制。\","
                    + "\"url\":\"https://www.jinse.com/\",\"source\":\"金色财经\",\"time\":\"今日\"},"
                    + "{\"title\":\"以太坊生态基础设施更新频繁，开发活跃度提升\","
                    + "\"summary\":\"来自 PANews 与链捕手公开报道，开发者生态继续扩展。\","
                    + "\"url\":\"https://www.panewslab.com/\",\"source\":\"PANews\",\"time\":\"今日\"},"
                    + "{\"title\":\"合规与安全审计成为行业长期主线\","
                    + "\"summary\":\"巴比特与 Odaily 公开内容显示，合规投入持续增加。\","
                    + "\"url\":\"https://www.8btc.com/\",\"source\":\"巴比特\",\"time\":\"今日\"}"
                    + "]}";

    private MinimalNewsFallbackData() {
    }

    static List<MinimalNewsItem> getItems(String category) {
        String json;
        if (MinimalNewsRepository.CATEGORY_TECH.equals(category)) {
            json = TECH_JSON;
        } else if (MinimalNewsRepository.CATEGORY_FINANCE.equals(category)) {
            json = FINANCE_JSON;
        } else if (MinimalNewsRepository.CATEGORY_BLOCK.equals(category)) {
            json = BLOCK_JSON;
        } else {
            json = HOT_JSON;
        }
        return parseItems(json, category);
    }

    private static List<MinimalNewsItem> parseItems(String json, String category) {
        List<MinimalNewsItem> result = new ArrayList<MinimalNewsItem>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) {
                return result;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                MinimalNewsItem item = new MinimalNewsItem();
                item.category = category;
                item.title = safe(one.optString("title"), "简讯");
                item.summary = safe(one.optString("summary"), "暂无摘要");
                item.articleUrl = safe(one.optString("url"), "https://www.xinhuanet.com/");
                item.source = safe(one.optString("source"), "公开资讯");
                item.publishTime = safe(one.optString("time"), "今日");
                item.imageUrl = "";
                result.add(item);
            }
        } catch (Throwable ignore) {
        }
        return result;
    }

    private static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        return text.length() == 0 ? fallback : text;
    }
}
