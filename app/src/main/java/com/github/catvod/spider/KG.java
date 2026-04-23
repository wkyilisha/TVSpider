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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            String preview = (len > 1000 ? html.substring(0, 1000) : html).trim().replace("\n", " ");
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

    // === 🚀 核心吸收 KG：帶防盜鏈破解的圖片代理邏輯 ===
    private String getPicUrl(String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        if (rule.optInt("pic_proxy", 0) == 1) {
            StringBuilder sb = new StringBuilder(pic);
            JSONObject picHd = rule.has("pic_headers") ? rule.optJSONObject("pic_headers") : rule.optJSONObject("headers");
            if (picHd != null) {
                Iterator<String> keys = picHd.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    sb.append("@").append(key).append("=").append(replaceStepVars(picHd.optString(key)));
                }
            }
            if (!sb.toString().contains("User-Agent")) {
                sb.append("@User-Agent=").append(rule.optString("ua", "Mozilla/5.0"));
            }
            return sb.toString();
        }
        return pic;
    }

    // === 🚀 核心吸收 KG：bfjx 鏈式解析邏輯 (精準嵌入 playerContent) ===
    private String processBfjxStep(String ruleLine, String currentHtml, int index) throws Exception {
        String processed = ruleLine.trim();
        logger("⚡ [bfjx Step " + index + "]: " + processed);
        
        // 1. 自動提取 HTML 中的變量並替換 (支持模糊匹配)
        Pattern extractPattern = Pattern.compile("([^+&]*?[+&]?[a-zA-Z0-9_*]*?&&[^+&]*)");
        Matcher m = extractPattern.matcher(processed);
        while (m.find()) {
            String expr = m.group(1);
            String extracted = extract(currentHtml, expr);
            processed = processed.replace(expr, extracted);
        }

        processed = replaceStepVars(processed);

        // 2. 解析自定義請求頭 [請求頭:k$v#k2$v2]
        Map<String, String> headers = new HashMap<>();
        Matcher hm = Pattern.compile("\\[請求頭:(.*?)\\]").matcher(processed);
        if (hm.find()) {
            for (String pair : hm.group(1).split("#")) {
                String[] kv = pair.split("\\$");
                if (kv.length == 2) headers.put(kv[0], replaceStepVars(kv[1]));
            }
            processed = processed.replace(hm.group(0), "");
        }

        // 3. 執行請求
        String method = processed.contains(";post;") ? "post" : "get";
        String[] parts = processed.split(";post;");
        String url = parts[0].trim();
        
        OkResult res;
        if (method.equals("get")) {
            res = OkHttp.get(url, null, headers.isEmpty() ? getHeaders(null) : headers);
        } else {
            Map<String, String> params = new HashMap<>();
            String body = parts[1].trim();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 0) params.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
            res = OkHttp.post(url, params, headers);
        }

        String resBody = res.getBody();
        if (ruleLine.contains("[base64]")) {
            resBody = new String(android.util.Base64.decode(resBody, android.util.Base64.DEFAULT), "UTF-8");
        }
        return resBody;
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
            } else if (url.startsWith("/") && !url.startsWith("//") && !url.contains("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
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
            vod.put("vod_pic", getPicUrl(extract(doc, rule.optString("dt_pic")))); // 🚀 圖片代理
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
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";
            
            JSONObject play = rule.getJSONObject("play");
            varPool.clear();
            varPool.put("play_id", url);
            
            String currentHtml = "";
            // 🚀 KG 核心吸收：優先檢查是否配置了 bfjx1-bfjx10 的鏈式解析
            boolean hasBfjx = false;
            for (int j = 1; j <= 10; j++) {
                String bfjxKey = "bfjx" + j;
                if (play.has(bfjxKey)) {
                    if (!hasBfjx) {
                        currentHtml = OkHttp.string(url, getHeaders(null));
                        hasBfjx = true;
                    }
                    currentHtml = processBfjxStep(play.getString(bfjxKey), currentHtml, j);
                    varPool.put("bfjx_tmp", currentHtml);
                    logCheck("bfjx Step " + j, currentHtml, true);
                }
            }

            // 🚀 如果跑了 bfjx，最後判斷是否需要 Base64 解碼輸出
            if (hasBfjx) {
                String finalUrl = currentHtml.trim();
                if (!finalUrl.startsWith("http")) finalUrl = new String(android.util.Base64.decode(finalUrl, android.util.Base64.DEFAULT), "UTF-8");
                String bfjxRes = buildFinalResult(play, finalUrl);
                logger("<br><span style='color:#16a085;'>🏁 <b>[bfjx 解析成功]</b></span><br><code>" + bfjxRes + "</code>");
                return bfjxRes;
            }

            // 🚀 原有 KaiGe Steps 邏輯
            JSONArray steps = play.optJSONArray("steps");
            for (int i = 0; i < (steps != null ? steps.length() : 0); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                
                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);
                StringBuilder hdLog = new StringBuilder("<details style='margin:5px 0;'><summary style='color:#0077ff;font-size:11px;cursor:pointer;'>📤 查看請求頭</summary><div style='color:#666;font-size:10px;padding:5px;background:#f9f9f9;border-left:2px solid #0077ff;margin-top:5px;'>");
                for (Map.Entry<String, String> entry : headers.entrySet()) hdLog.append("<b>").append(entry.getKey()).append(":</b> ").append(entry.getValue()).append("<br>");
                hdLog.append("</div></details>");
                logger(hdLog.toString());
                
                OkResult res = method.equals("post") ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers) : OkHttp.get(stepUrl, null, headers);
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
                        logger("  └ 💡 提取變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }
            return buildFinalResult(play, replaceStepVars(play.optString("final_output", "{final_url}")));

        } catch (Exception e) {
            String baseUrl = this.siteUrl;
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            String finalId = (id != null && !id.startsWith("http")) ? (id.startsWith("/") ? (baseUrl + id) : (baseUrl + "/" + id)) : id;
            String errorResult = "{\"parse\":1,\"url\":\"" + finalId + "\",\"header\":{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\"}}";
            logger("<br><span style='color:#e74c3c;'>🚨 <b>[解析異常]</b></span><br>原因: " + e.getMessage() + "<br>返回: <code>" + errorResult + "</code>");
            return errorResult;
        }
    }

    private String buildFinalResult(JSONObject play, String finalUrl) throws Exception {
        if (finalUrl.trim().startsWith("{") && finalUrl.contains("\"parse\"")) return finalUrl;
        JSONObject resJson = new JSONObject();
        resJson.put("parse", 0);
        resJson.put("url", finalUrl);
        resJson.put("header", play.optJSONObject("play_headers") != null ? play.optJSONObject("play_headers") : new JSONObject().put("User-Agent", rule.optString("ua")));
        return resJson.toString();
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
                vod.put("vod_pic", getPicUrl(extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))))); // 🚀 圖片代理
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    // === 🚀 核心吸收 KG：增強型提取器 (支持 CSS + * 模糊匹配) ===
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String workRule = ruleStr.replace("@", "&&");
            
            // 吸收 KG 的 * 模糊匹配邏輯
            if (workRule.contains("&&")) {
                String[] parts = workRule.split("&&");
                String selector = parts[0].trim();
                String right = parts[1].trim();
                
                // 執行 CSS 定位或對整個 HTML 操作
                String source;
                if (selector.isEmpty() || selector.equals("html")) {
                    source = root.toString();
                } else {
                    Element target = (root instanceof Document) ? ((Document) root).selectFirst(selector) : ((Element) root).selectFirst(selector);
                    if (target == null) return "";
                    if (isAttr(right)) return target.attr(right).trim();
                    if (right.equals("text")) return target.text().trim();
                    source = target.outerHtml();
                }
                
                // 模糊匹配核心邏輯
                if (right.contains("*")) {
                    String[] anchors = right.split("\\*");
                    int pos = 0;
                    for (String a : anchors) {
                        int i = source.indexOf(a.trim(), pos);
                        if (i == -1) return "";
                        pos = i + a.trim().length();
                    }
                    String lastAnchor = anchors[anchors.length - 1]; // 這裡假設用戶習慣，最後一段可能是結尾或者還需要截取
                    // 如果用戶寫了 left*middle*right&&end
                    return extractString(source.substring(pos), parts.length > 2 ? parts[2] : ""); 
                }
                return extractString(source, right);
            }
            
            if (root instanceof Element) {
                Element el = ((Element) root).selectFirst(workRule);
                return el != null ? el.text().trim() : "";
            }
        } catch (Exception e) {}
        return "";
    }

    private boolean isAttr(String s) {
        String t = s.toLowerCase();
        return t.equals("href") || t.equals("title") || t.equals("src") || t.startsWith("data-") || t.equals("value");
    }

    private String extractString(String content, String ruleStr) {
        try {
            if (!ruleStr.contains("&&")) return content.split(" ")[0].trim(); // 兜底
            String[] p = ruleStr.split("&&");
            int s = content.indexOf(p[0].trim());
            if (s == -1) return "";
            s += p[0].trim().length();
            int e = content.indexOf(p[1].trim(), s);
            return (e != -1) ? content.substring(s, e).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", rule.optString("host")).replace("{ua}", rule.optString("ua"));
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
