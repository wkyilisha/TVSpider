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

public class KG extends Spider {
    private String siteUrl = ""; 
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    private void logger(String msg) {
        try {
            Proxy.log(msg);
        } catch (Exception ignored) {}
    }

    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | " + len + " 字符");
        if (showSource) {
            String preview = (len > 500 ? html.substring(0, 500) : html)
                .trim().replace("\n", " ").replace("\r", " ");
            logger("📄 [源碼預覽]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");
            
            if (TextUtils.isEmpty(extend)) {
                logger("🚨 [系統] 初始化失敗: 配置路徑為空");
                return;
            }

            String json;
            if (extend.startsWith("http")) {
                // 🚀 關鍵修復：手動過濾掉可能包含中文的 Referer 隱患
                Map<String, String> initHeaders = new HashMap<>();
                initHeaders.put("Referer", ""); // 清空 Referer，防止 OkHttp 報錯
                
                // 使用最原始的 OkHttp 請求，避免被 smartRequest 裡的自動 Header 帶偏
                OkResult res = OkHttp.get(extend, null, initHeaders);
                json = res.getBody();
            } else {
                json = extend;
            }

            if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) {
                logger("🚨 [系統] 初始化失敗: 讀取到的 JSON 格式不正確");
                return;
            }

            this.rule = new JSONObject(json);
            // 🚀 從配置中自動提取域名
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));

            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logger("🌐 [系統] 域名自動綁定: " + this.siteUrl);
            
        } catch (Exception e) {
            // 這裡會捕獲到 Unexpected char 報錯並顯示
            logger("🚨 [系統] 初始化崩潰: " + e.getMessage());
        }
    }


    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"))
                    .replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;
            
            // 🚀 升級：使用 KaiGeNet
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, "get", url, null, getHeaders(null));
            logCheck("分類", res.getBody(), false);
            return parseList(res.getBody(), pg, false);
        } catch (Exception ex) { return "{\"list\":[]}"; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            logger("🔍 [搜索] 關鍵字: " + key + " | 網址: " + url);
            // 🚀 升級：使用 KaiGeNet
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, "get", url, null, getHeaders(null));
            logCheck("搜索", res.getBody(), false);
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            return "{\"list\":[]}"; 
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            
            // 🚀 升級：使用 KaiGeNet
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, "get", url, null, getHeaders(null));
            String html = res.getBody();
            logCheck("詳情", html, false);

            Document doc = Jsoup.parse(html);
            
            // 🚀 升級：智慧保底模式
            // 首先嘗試用 KaiGeSmart 掃描全圖
            JSONObject smartVod = KaiGeSmart.parseDetail(html);
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            // 策略：如果規則有寫就用規則，規則沒寫或抓不到就用大腦智慧識別
            String name = extract(doc, rule.optString("dt_name"));
            vod.put("vod_name", TextUtils.isEmpty(name) ? smartVod.optString("vod_name") : name);
            
            String pic = extract(doc, rule.optString("dt_pic"));
            vod.put("vod_pic", TextUtils.isEmpty(pic) ? smartVod.optString("vod_pic") : pic);
            
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            
            String actor = extract(doc, rule.optString("dt_actor"));
            vod.put("vod_actor", TextUtils.isEmpty(actor) ? smartVod.optString("vod_actor") : actor);
            
            String director = extract(doc, rule.optString("dt_director"));
            vod.put("vod_director", TextUtils.isEmpty(director) ? smartVod.optString("vod_director") : director);
            
            String content = extract(doc, rule.optString("dt_content"));
            vod.put("vod_content", TextUtils.isEmpty(content) ? smartVod.optString("vod_content") : content);

            // 🚀 升級：播放列表處理
            if (TextUtils.isEmpty(rule.optString("dt_list"))) {
                // 如果規則沒寫列表選擇器，直接用大腦識別出的線路
                vod.put("vod_play_from", smartVod.optString("vod_play_from"));
                vod.put("vod_play_url", smartVod.optString("vod_play_url"));
            } else {
                // 如果規則寫了，則執行你原本的精確配對邏輯
                processOriginalDetail(doc, vod);
            }

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { 
            return ""; 
        }
    }

    // 提取出的原本詳情解析邏輯（保持凱哥原汁原味）
    private void processOriginalDetail(Document doc, JSONObject vod) throws Exception {
        String fromRule = rule.optString("dt_from");
        String listRule = rule.optString("dt_list");
        String cssFrom = fromRule;
        if (fromRule.contains("&&")) {
            String[] parts = fromRule.split("&&");
            cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
        }
        Elements fromElements = doc.select(cssFrom);
        List<String> fList = new ArrayList<>();
        List<String> pLists = new ArrayList<>();

        for (Element from : fromElements) {
            String sourceName = from.text().trim();
            if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (fromElements.indexOf(from) + 1);
            Element nextList = null;
            Element p = from.parent(); 
            while (p != null && nextList == null) {
                Element sibling = p.nextElementSibling();
                while (sibling != null) {
                    nextList = sibling.selectFirst(listRule);
                    if (nextList != null) break;
                    sibling = sibling.nextElementSibling();
                }
                if (nextList != null) break;
                p = p.parent();
                if (p != null && p.tagName().equals("body")) break;
            }
            if (nextList == null) {
                Elements allLists = doc.select(listRule);
                int idx = fromElements.indexOf(from);
                if (idx < allLists.size()) nextList = allLists.get(idx);
            }
            if (nextList != null) {
                fList.add(sourceName);
                pLists.add(nextList.outerHtml()); 
            }
        }

        List<String> playList = new ArrayList<>();
        for (int i = 0; i < pLists.size(); i++) {
            List<String> urls = new ArrayList<>();
            Document listDoc = Jsoup.parse(pLists.get(i));
            Elements aElements = listDoc.select("a");
            for (Element a : aElements) {
                String pName = extract(a, rule.optString("dt_list_name")); 
                String pUrl = extract(a, rule.optString("dt_list_url"));
                if (!pName.isEmpty() && !pUrl.isEmpty()) urls.add(pName + "$" + pUrl);
            }
            playList.add(TextUtils.join("#", urls));
        }
        vod.put("vod_play_from", TextUtils.join("$$$", fList));
        vod.put("vod_play_url", TextUtils.join("$$$", playList));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String originalUrl = id.startsWith("/") && !id.startsWith("//") ? this.siteUrl + id : id;
        try {
            logger("<br>🎬 <b>[播放解析啟動]</b>");
            varPool.clear();
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl); 

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = (steps != null ? steps.length() : 0);
            boolean finalStepSuccess = false;

            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject res = new JSONObject();
                res.put("parse", isStream ? 0 : 1);
                res.put("url", originalUrl);
                res.put("header", getPlayHeaders(play));
                return res.toString();
            }

            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break;
                JSONObject step = steps.getJSONObject(i);
                String stepUrl = replaceStepVars(step.optString("url", varPool.get("final_url")));
                String method = step.optString("method", "get");
                
                // 🚀 升級：播放步驟也使用 KaiGeNet
                OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl, replaceStepVars(step.optString("body")), getHeaders(step.optJSONObject("headers")));
                String html = res.getBody();
                logCheck("解析 Step " + (i + 1), html, true);

                JSONObject vars = step.optJSONObject("vars");
                if (vars != null) {
                    boolean currentStepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String vRule = vars.optString(k);
                        String val = "";
                        if (vRule.startsWith("json:")) {
                            try { val = new JSONObject(html).optString(vRule.substring(5)); } catch (Exception e) { val = ""; }
                        } else {
                            val = KaiGeEngine.doExtract(html, vRule, this.siteUrl).value;
                        }
                        if (!TextUtils.isEmpty(val)) {
                            varPool.put(k, val);
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val); 
                                currentStepAnyOk = true;
                            }
                        }
                    }
                    if (i == stepCount - 1 && currentStepAnyOk) finalStepSuccess = true;
                }
            } 

            String finalUrl = varPool.get("final_url").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalStepSuccess || finalHasStream) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", (pValue == 0) ? finalUrl : originalUrl);
            resJson.put("header", getPlayHeaders(play));
            return resJson.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + originalUrl + "\",\"header\":{}}";
        }
    }

    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
            } catch (Exception ignored) {}
        }
        return headJson;
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            Elements items = doc.select(rule.optString(prefix + "item", rule.optString("cate_item")));
            for (Element item : items) {
                // 🚀 升級：調用 KaiGeSmart 智慧識別列表項
                JSONObject smartVod = KaiGeSmart.parseList(item);
                
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                // ID 保底處理
                if (TextUtils.isEmpty(vId)) vId = smartVod.optString("vod_id");
                vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                
                String vName = extract(item, rule.optString(prefix + "name", rule.optString("cate_name")));
                vod.put("vod_name", TextUtils.isEmpty(vName) ? smartVod.optString("vod_name") : vName);
                
                String vPic = extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")));
                vod.put("vod_pic", TextUtils.isEmpty(vPic) ? smartVod.optString("vod_pic") : vPic);
                
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            if (!ruleStr.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    if (ruleStr.contains("@")) {
                        String[] parts = ruleStr.split("@");
                        Element target = parts[0].trim().isEmpty() ? el : el.selectFirst(parts[0].trim());
                        return target != null ? target.attr(parts[1].trim()) : "";
                    } else {
                        Element target = el.selectFirst(ruleStr);
                        return target != null ? target.text() : "";
                    }
                }
            } else {
                String content = (root instanceof Document) ? ((Document) root).outerHtml() : (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
                return KaiGeEngine.doExtract(content, ruleStr, this.siteUrl).value;
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", this.siteUrl);
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
    try {
        logger("🏠 [主頁] 正在加載分類導航...");
        JSONArray classes = rule.optJSONArray("classes");
        
        if (classes == null || classes.length() == 0) {
            logger("🚨 [主頁] 警告：JSON 規則中未定義 classes 或格式錯誤");
            return "";
        }

        // 🚀 凱哥特製：字段自動對接
        // 很多 JSON 寫的是 type_name/type_id，有些殼子要的是 name/id
        // 我們在這裡做一個轉換，保證 100% 顯示
        JSONArray resultClasses = new JSONArray();
        for (int i = 0; i < classes.length(); i++) {
            JSONObject oldCate = classes.getJSONObject(i);
            JSONObject newCate = new JSONObject();
            
            String name = oldCate.optString("type_name", oldCate.optString("name"));
            String id = oldCate.optString("type_id", oldCate.optString("id"));
            
            newCate.put("type_name", name);
            newCate.put("type_id", id);
            resultClasses.put(newCate);
        }

        logger("✅ [主頁] 分類加載成功，共 " + resultClasses.length() + " 個頻道");
        
        JSONObject result = new JSONObject();
        result.put("class", resultClasses);
        
        // 如果規則裡有篩選數據(filters)，也可以在這裡放進去
        if (rule.has("filters")) {
            result.put("filters", rule.optJSONObject("filters"));
        }
        
        return result.toString();
    } catch (Exception e) {
        logger("🚨 [主頁異常]: " + e.getMessage());
        return "";
    }
}
}
