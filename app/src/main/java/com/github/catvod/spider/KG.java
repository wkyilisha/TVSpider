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
            // 1. 動態讀取分類配置
            String method = rule.optString("cate_method", "get").toLowerCase();
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"));
            String body = rule.optString("cate_body", "");

            // 1. 確定最終 TID (處理電視劇子類篩選覆蓋)
            String rTid = tid;
            if (e != null) {
                if (e.containsKey("tid")) rTid = e.get("tid");
                else if (e.containsKey("type_id")) rTid = e.get("type_id");
            }

            // 2. 處理基礎變量與篩選變量替換
            url = url.replace("{tid}", URLEncoder.encode(rTid, "UTF-8")).replace("{pg}", pg);
            if (!TextUtils.isEmpty(body)) body = body.replace("{tid}", rTid).replace("{pg}", pg);

            String[] filterKeys = {"area", "class", "year", "by", "lang", "letter", "字母"};
            for (String key : filterKeys) {
                String val = (e != null && e.containsKey(key)) ? e.get(key) : "";
                url = url.replace("{" + key + "}", URLEncoder.encode(val, "UTF-8"));
                if (!TextUtils.isEmpty(body)) body = body.replace("{" + key + "}", val);
            }

            if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            // 💡 凱哥監控：顯示完整請求鏈接與 POST 參數
            Proxy.log("<b style='color:#2ecc71;'>📂 [分類啟動]</b> " + url);
            if (method.equals("post") && !TextUtils.isEmpty(body)) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // --- 3. 基礎門票 (針對普通網站的 Session) ---
            if (pg.equals("1")) {
                KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null));
                try { Thread.sleep(200); } catch (Exception ignored) {}
            }

            // --- 4. 正式發起請求 ---
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();

            // 💡 凱哥監控：顯示返回數據長度
            Proxy.log("<b style='color:#3498db;'>📊 [數據返回]</b> 長度: " + (html != null ? html.length() : 0));

            // --- 5. 基礎重試補償 ---
            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                Proxy.log("<b style='color:#f1c40f;'>⚠️ 內容異常，嘗試二次刷新...</b>");
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
                html = res.getBody();
            }

            // --- 6. 交給解析器 ---
            logCheck("分類", html, false);
            return parseList(html, pg, false);
        } catch (Exception ex) { 
            Proxy.log("<b style='color:red;'>🚨 [分類異常]:</b> " + ex.getMessage());
            return "{\"list\":[]}"; 
        }
    }

@Override
    public String searchContent(String key, boolean quick) {
        try {
            // 1. 動態讀取規則中的方法、網址和 Body
            String method = rule.optString("search_method", "get").toLowerCase();
            String url = rule.optString("search_url");
            String body = rule.optString("search_body", "");

            // 2. 處理關鍵字替換 (🚀 修復二次編碼問題)
            if (method.equals("post")) {
                // POST 模式：直接用原始 key，交給底層 KaiGeNet/OkHttp 自動編碼，防止 % 變成 %25
                body = body.replace("{wd}", key);
            } else {
                // GET 模式：必須手動編碼，因為 URL 字符串拼接不支持原始中文
                url = url.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            }

            // 域名補全
            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            // 💡 凱哥監控：這裡是關鍵，看紫色和藍色日誌的輸出
            Proxy.log("<b style='color:#3498db;'>🔍 [搜索啟動]</b> 方法: " + method.toUpperCase());
            Proxy.log("<span style='color:#9b59b6;'>[搜索網址]</span> " + url);
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // 3. 🚀 關鍵修復：將寫死的 "get" 改為動態 method，將 null 改為 body
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            
            logCheck("搜索", res.getBody(), false);
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            Proxy.log("<b style='color:red;'>🚨 [搜索異常]:</b> " + e.getMessage());
            return "{\"list\":[]}"; 
        }
    }

@Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            
            // 1. 動態讀取詳情配置
            String method = rule.optString("detail_method", "get").toLowerCase();
            String url = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            String body = rule.optString("detail_body", "");

            // 2. 處理變量替換 (POST 傳原始值，GET 傳編碼值)
            if (method.equals("post")) {
                body = body.replace("{id}", id);
            } else {
                // 如果 URL 包含 {id} 佔位符則替換，否則保持原有拼接邏輯
                if (url.contains("{id}")) {
                    url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));
                }
            }

            // 💡 凱哥監控：詳情請求日誌
            Proxy.log("<b style='color:#f39c12;'>📋 [詳情啟動]</b> 方法: " + method.toUpperCase() + " | ID: " + id);
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // 3. 🚀 升級：使用動態 method 和 body 調用 KaiGeNet
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
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
            Proxy.log("<b style='color:red;'>🚨 [詳情異常]:</b> " + e.getMessage());
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
            // 🎬 啟動日誌
            Proxy.log("<b style='color:#e74c3c;'>🎬 [播放解析啟動]</b> 原始ID: " + originalUrl);
            
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
                Proxy.log("<b style='color:#2ecc71;'>🚀 [Direct] 無解析步驟，直接推送原始地址</b>");
                return res.toString();
            }

            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break;
                JSONObject step = steps.getJSONObject(i);
                String stepUrl = replaceStepVars(step.optString("url", varPool.get("final_url")));
                String method = step.optString("method", "get");
                
                // 💡 獲取當前步驟的請求頭
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                
                // 🚀 凱哥監控：Step 請求細節（含 URL 和 Headers）
                Proxy.log("<span style='color:#3498db;'>[Step " + (i + 1) + " 請求]</span> " + method.toUpperCase() + " -> " + stepUrl);
                Proxy.log("<span style='color:#9b59b6;'>[請求頭查看]</span> " + headers.toString());

            // 1. 發起請求
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl, replaceStepVars(step.optString("body")), getHeaders(step.optJSONObject("headers")));
            String html = res.getBody();
            
            // 🚀 凱哥暴力監控：不論 html 是否為空，通通打印！
            Proxy.log("<b style='color:#3498db;'>📥 [Step " + (i + 1) + " 返回監控]</b>");
            if (TextUtils.isEmpty(html)) {
                // 如果是空的，說明請求連通都沒通（可能是網址帶了特殊字符、引號或網絡超時）
                Proxy.log("<b style='color:red;'>❌ [致命] 返回內容完全為空！</b> 請檢查 URL 格式或網絡連通性。");
            } else {
                // 🚀 核心：強制噴出前 500 字符源碼，確保你在日誌能看到數據「真身」
                String preview = (html.length() > 500 ? html.substring(0, 500) : html)
                                .trim().replace("\n", " ").replace("\r", " ");
                Proxy.log("<div style='background:#2c3e50; color:#ecf0f1; padding:5px; border-left:5px solid #e74c3c;'>源碼預覽: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...</div>");
            }

            // 2. 繼續執行變量提取
            JSONObject vars = step.optJSONObject("vars");
if (vars != null) {
                    boolean currentStepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String vRule = vars.optString(k).trim(); // 去掉可能存在的空格
                        String val = "";
                        
                        // 🚀 1. 增強型 JSON 提取
                        if (vRule.startsWith("json:")) {
                            try {
                                String keyName = vRule.substring(5).trim(); // 拿到 "url"
                                JSONObject jsonObj = new JSONObject(html.trim());
                                val = jsonObj.optString(keyName);
                            } catch (Exception e) {
                                Proxy.log("   └─ <b style='color:red;'>❌ JSON 解析失敗:</b> " + e.getMessage());
                                val = "";
                            }
                        } else {
                            val = KaiGeEngine.doExtract(html, vRule, this.siteUrl).value;
                        }

                        // 🚀 2. 暴力清洗提取到的數據
                        if (!TextUtils.isEmpty(val)) {
                            // 幹掉所有反斜槓，把 \/ 變成 /
                            val = val.replace("\\/", "/").replace("\\", "").trim();
                            
                            varPool.put(k, val);
                            
                            // 💡 變量提取監控
                            Proxy.log("    └─ <span style='color:#f1c40f;'>[提取成功]</span> " + k + " = " + (val.length() > 80 ? val.substring(0, 80) + "..." : val));

                            // 🚀 3. 核心鎖定：只要 key 包含 url，就更新最終地址
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val); 
                                currentStepAnyOk = true;
                            }
                        } else {
                            Proxy.log("    └─ <b style='color:#95a5a6;'>⚠️ [提取為空]</b> 鍵: " + k + " | 規則: " + vRule);
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
            
            // 🚀 最終推送 JSON 日誌
            String finalPush = resJson.toString();
            Proxy.log("<b style='color:#2ecc71;'>🚀 [Final:推送 JSON]</b>");
            Proxy.log("<div style='background:#1a1a1a; color:#00ff00; padding:8px; border:1px solid #2ecc71; font-family:monospace;'>" + finalPush + "</div>");

            return finalPush;
        } catch (Exception e) {
            Proxy.log("<b style='color:red;'>❌ [播放解析崩潰]:</b> " + e.getMessage());
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
        JSONArray list = new JSONArray();
        String prefix = isSearch ? "sc_" : "cate_";
        
        if (html.trim().startsWith("{") && html.contains("\"list\"")) {
            JSONObject json = new JSONObject(html);
            JSONArray array = json.optJSONArray("list");
            if (array != null) {
                String detailTemplate = rule.optString("detail_url", "");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    
                    String vId = item.optString("id");
                    if (!TextUtils.isEmpty(vId)) {
                        if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                            vod.put("vod_id", detailTemplate.replace("{id}", vId));
                        } else {
                            vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                    }

                    vod.put("vod_name", item.optString("name"));
                    
                    String vPic = item.optString("pic");
                    if (!TextUtils.isEmpty(vPic) && vPic.startsWith("//")) vPic = "http:" + vPic;
                    vod.put("vod_pic", vPic);
                    
                    vod.put("vod_remarks", item.optString("remarks"));
                    
                    if (vod.has("vod_id")) list.put(vod);
                }
            }
        } else {
            Document doc = Jsoup.parse(html);
            String itemRule = rule.optString(prefix + "item", rule.optString("cate_item"));
            Elements items = doc.select(itemRule);
            String detailTemplate = rule.optString("detail_url", "");

            for (Element item : items) {
                JSONObject smartVod = KaiGeSmart.parseList(item);
                JSONObject vod = new JSONObject();
                
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                if (TextUtils.isEmpty(vId)) vId = smartVod.optString("vod_id");
                
                if (!TextUtils.isEmpty(vId)) {
                    if (isSearch && !detailTemplate.isEmpty() && !vId.startsWith("http")) {
                        vod.put("vod_id", detailTemplate.replace("{id}", vId));
                    } else {
                        vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                    }
                }

                String vName = extract(item, rule.optString(prefix + "name", rule.optString("cate_name")));
                vod.put("vod_name", TextUtils.isEmpty(vName) ? smartVod.optString("vod_name") : vName);
                
                String vPic = extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")));
                if (TextUtils.isEmpty(vPic)) vPic = smartVod.optString("vod_pic");
                if (!TextUtils.isEmpty(vPic) && vPic.startsWith("//")) vPic = "http:" + vPic;
                vod.put("vod_pic", vPic);
                
                String vRemarks = extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks")));
                vod.put("vod_remarks", TextUtils.isEmpty(vRemarks) ? smartVod.optString("vod_remarks") : vRemarks);

                if (vod.has("vod_id")) list.put(vod);
            }
        }
        return new JSONObject().put("list", list).put("page", pg).toString();
    } catch (Exception e) { 
        return "{\"list\":[]}"; 
    }
}

private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            // 🚀 關鍵點 1：讓規則支持變量替換
            // 這樣你才能在規則裡寫 "{host}/vod/" 或者使用之前 vars 存下的 {kkk}
            String realRule = replaceStepVars(ruleStr);

            // 判斷是 Jsoup 選擇器還是 KaiGeEngine 規則
            if (!realRule.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    if (realRule.contains("@")) {
                        String[] parts = realRule.split("@");
                        Element target = parts[0].trim().isEmpty() ? el : el.selectFirst(parts[0].trim());
                        return target != null ? target.attr(parts[1].trim()) : "";
                    } else {
                        Element target = el.selectFirst(realRule);
                        return target != null ? target.text() : "";
                    }
                }
            } else {
                // 🚀 關鍵點 2：調用凱哥 2.0 引擎
                // 此時 realRule 已經是替換好變量的完整規則了
                String content = (root instanceof Document) ? ((Document) root).outerHtml() : (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
                return KaiGeEngine.doExtract(content, realRule, this.siteUrl).value;
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
