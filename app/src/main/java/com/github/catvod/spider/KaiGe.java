package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KaiGe extends Spider {
    private String siteUrl = ""; // 🚀 全局域名變量
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private void logger(String msg) {
        logExecutor.execute(() -> Proxy.log(msg));
    }

    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗：HTML 為空");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | 長度: " + len + " 字節");
        if (showSource) {
            String preview = (len > 7000 ? html.substring(0, 7000) : html).trim().replace("\n", " ");
            logger("📄 [源碼預覽]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

@Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            
            // 🚀 從配置中自動提取域名，適配所有網站
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));
            
            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logger("🌐 [系統] 域名自動綁定: " + this.siteUrl);
        } catch (Exception e) {
            logger("🚨 [系統] 初始化失敗: " + e.getMessage());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"))
                    .replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("📂 [分類] 請求網址: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("分類", res.getBody(), false);
            return parseList(res.getBody(), pg, false);
        } catch (Exception ex) { return "{\"list\":[]}"; }
    }

@Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            
            if (url.contains("{host}")) {
                url = url.replace("{host}", this.siteUrl);
            } 
            else if (url.startsWith("/") && !url.startsWith("//") && !url.contains("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                url = baseUrl + url;
            }

            logger("🔍 [搜索] 關鍵字: " + key + " | 網址: " + url);
            
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("搜索", res.getBody(), false);
            
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            logger("🚨 [搜索異常]: " + e.getMessage());
            return "{\"list\":[]}"; 
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [詳情] 正在解析內容: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("詳情", res.getBody(), false);
            
            Document doc = Jsoup.parse(res.getBody());
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));
            
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fList = new ArrayList<>();
            for (Element f : froms) fList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fList));

            Elements lists = doc.select(rule.optString("dt_list"));
            List<String> pLists = new ArrayList<>();
            // 精確子定位規則
            String listNameRule = rule.optString("dt_list_name", "a");
            String listUrlRule = rule.optString("dt_list_url", "a@href");

            for (int i = 0; i < lists.size(); i++) {
                Element group = lists.get(i);
                List<String> urls = new ArrayList<>();
                Elements items = group.select("a"); 
                for (Element item : items) {
                    String name = extract(item, listNameRule);
                    String link = extract(item, listUrlRule);
                    if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(link)) {
                        urls.add(name + "$" + link);
                    }
                }
                String source = i < fList.size() ? fList.get(i) : "線路" + (i + 1);
                logger("✅ [詳情] 線路 [" + source + "] 成功提取選集: " + urls.size() + " 個");
                pLists.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", pLists));
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
try {
            String url = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
            logger("<br>🎬 <b>[播放解析啟動]</b>: " + url);

            // ❌ 注意：原代碼這行 if (!rule.has("play")) ... 必須刪掉或註釋掉，否則會直接返回 parse:0 導致後面的邏輯跑不到
            // if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");

            // --- 🚀 凱哥分流邏輯：沒 Step 直接回嗅探，有 Step 才跑解析 ---
            int stepCount = (steps != null ? steps.length() : 0);
            boolean isStream = url.toLowerCase().contains(".m3u8") || url.toLowerCase().contains(".mp4") || url.toLowerCase().contains(".flv");

            // 🔥 第一關：如果完全沒有步驟，直接秒回（除非網址本身就是流媒體後綴）
            if (stepCount == 0) {
                int pValue = isStream ? 0 : 1;
                JSONObject res = new JSONObject();
                res.put("parse", pValue);
                res.put("url", url);
                res.put("header", getPlayHeaders(play)); 
                String result = res.toString();

                logger("<br><span style='color:#e67e22;'>🏁 <b>[無步驟模式]</b></span>" +
                        "<br><b>判定原因:</b> 規則無 Steps" +
                        "<br><b>返回類型:</b> " + (pValue == 0 ? "直連" : "嗅探") +
                        "<br><b>完整返回:</b> <code style='color:#2980b9;'>" + result + "</code>");
                return result;
            } // <--- 這裡就是你說的原代碼最後那個括號，執行到這就 return 了

            // --- 🚀 第二關：有 Step 的情況下，初始化並執行解析 ---
            varPool.clear();
            varPool.put("play_id", url);
            varPool.put("final_url", url); // 初始值保底
            String currentHtml = "";

            for (int i = 0; i < stepCount; i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);
                
                OkResult res = method.equals("post") 
                    ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers)
                    : OkHttp.get(stepUrl, null, headers);
                
                currentHtml = res.getBody();
                logCheck("解析 Step " + (i+1), currentHtml, true);

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String vRule = vars.getString(k);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(k, val);
                        // 🚀 同步更新 final_url，確保後面的邏輯能拿到解析後的地址
                        if (k.equals("final_url") || k.equals("url")) varPool.put("final_url", val);
                        logger("  └ 💡 提取變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }

            // --- 🚀 第三關：有 Step 執行後的判定邏輯 ---
            String finalUrl = varPool.get("final_url");
            if (finalUrl == null) finalUrl = url;

            // 重新判定解析後的地址是否有視頻後綴
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalHasStream || !finalUrl.equals(url)) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", finalUrl);
            resJson.put("header", getPlayHeaders(play));
            String result = resJson.toString();

            logger("<br><span style='color:#16a085;'>🏁 <b>[解析返回診斷]</b></span>" +
                   "<br><b>判定原因:</b> Step 解析完成" +
                   "<br><b>最終地址:</b> " + finalUrl +
                   "<br><b>完整返回:</b> <code style='color:#2980b9;'>" + result + "</code>");

            return result;

        } catch (Exception e) { 
            String finalId = id;
            if (id != null && !id.startsWith("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    finalId = id.startsWith("/") ? (baseUrl + id) : (baseUrl + "/" + id);
                }
            }
            String errorResult = "{\"parse\":1,\"url\":\"" + finalId + "\",\"header\":{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\",\"Referer\":\"" + this.siteUrl + "/\",\"Origin\":\"" + this.siteUrl + "\"}}";
            logger("<br><span style='color:#e74c3c;'>🚨 <b>[解析異常拋給殼子]</b></span><br>原因: " + e.getMessage() + "<br>返回: <code>" + errorResult + "</code>");
            return errorResult;
        }
    }

    // 🚀 在類末尾補上這個提取播放頭的輔助方法，保證代碼簡潔
    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
                headJson.put("Origin", this.siteUrl);
            } catch (Exception e) {}
        }
        return headJson;
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            Elements items = doc.select(rule.optString(prefix + "item", rule.optString("cate_item")));
            logger("📊 [列表] 成功提取項目數量: " + items.size());
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                vod.put("vod_id", vId.startsWith("http") ? vId : rule.optString("host") + (vId.startsWith("/") ? "" : "/") + vId);
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

private String extract(Object root, String ruleStr) {
    try {
        if (TextUtils.isEmpty(ruleStr) || root == null) {
            return "";
        }

        // 🚀 【新增邏輯】處理純源碼字符串（Step 2 的關鍵）
        if (root instanceof String) {
            String content = (String) root;
            String workRule = ruleStr.replace("@", "&&");
            
            if (workRule.contains("&&")) {
                String result = extractString(content, workRule);
                // 💡 日誌：監控字符串截取結果
                if (result.isEmpty()) {
                    String start = workRule.split("&&")[0].trim();
                    if (!content.contains(start)) {
                        logger("⚠️ [提取失敗] 源碼中完全找不到起點關鍵詞: " + start);
                    } else {
                        logger("⚠️ [提取失敗] 找到起點但未找到匹配的終點，規則: " + workRule);
                    }
                } else {
                    logger("✅ [提取成功] 規則: " + workRule + " -> 提取值: " + result);
                }
                return result;
            } else {
                // CSS 選擇器日誌
                Document doc = Jsoup.parse(content);
                Element el = doc.selectFirst(workRule);
                String res = el != null ? el.text().trim() : "";
                logger("🔍 [CSS提取] 規則: " + workRule + " -> 結果: " + res);
                return res;
            }
        }

        // 🚀 【原有邏輯】處理 Document / Element 對象
        String workRule = ruleStr.replace("@", "&&");
        if (workRule.contains("&&")) {
            String[] parts = workRule.split("&&");
            String selector = parts[0].trim();
            String second = parts[1].trim();

            Element target = (root instanceof Document)
                    ? ((Document) root).selectFirst(selector)
                    : ((Element) root).selectFirst(selector);

            if (target != null) {
                String res = "";
                if (isAttr(second)) {
                    res = target.attr(second).trim();
                } else if (second.equals("text") || second.isEmpty()) {
                    res = target.text().trim();
                } else {
                    res = extractString(target.outerHtml(), second);
                }
                logger("✅ [對象提取] 選擇器: " + selector + " -> 結果: " + res);
                return res;
            } else {
                logger("❌ [對象提取] 找不到選擇器節點: " + selector);
            }
        }

        if (root instanceof Element) {
            Element el = ((Element) root).selectFirst(workRule);
            return el != null ? el.text().trim() : "";
        }
    } catch (Exception e) {
        logger("🚨 [提取崩潰] 錯誤原因: " + e.getMessage());
    }
    return "";
}

    private boolean isAttr(String s) {
        String t = s.toLowerCase();
        return t.equals("href") || t.equals("title") || t.equals("src") || t.startsWith("data-") || t.equals("value");
    }

    private String extractString(String content, String ruleStr) {
        try {
            if (content == null || !ruleStr.contains("&&")) return "";
            
            String[] p = ruleStr.split("&&");
            String start = p[0].trim();
            String end = p[1].trim();

            // 🚀 核心調用
            String result = com.github.catvod.utils.Util.cut(content, start, end);
            
            // 💡 凱哥專用調試日誌：如果提取是空的，我們就打印原因
            if (result.isEmpty()) {
                if (!content.contains(start)) {
                    logger("⚠️ [匹配失敗] 源碼中找不到起點: " + start);
                } else {
                    logger("⚠️ [匹配失敗] 找到起點但找不到終點: " + end);
                }
            }
            
            return result;
            
        } catch (Exception e) { 
            logger("❌ [提取崩潰]: " + e.getMessage());
            return ""; 
        }
    }

    private String replaceStepVars(String text) {
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", rule.optString("host"));
    }

    private Map<String, String> getHeaders(JSONObject custom) {
        Map<String, String> hb = new HashMap<>();
        hb.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = custom != null ? custom : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                hb.put(k, replaceStepVars(hd.optString(k)));
            }
        }
        return hb;
    }

    @Override
    public String homeContent(boolean filter) {
        try { return new JSONObject().put("class", rule.optJSONArray("classes")).toString(); } catch (Exception e) { return ""; }
    }
}
