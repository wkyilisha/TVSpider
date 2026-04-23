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
            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
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
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("🔍 [搜索] 關鍵字: " + key + " | 網址: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("搜索", res.getBody(), false);
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { return "{\"list\":[]}"; }
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
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";
            
            JSONObject play = rule.getJSONObject("play");
            JSONArray steps = play.optJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);
            
            String currentHtml = "";
            for (int i = 0; i < (steps != null ? steps.length() : 0); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                
                // 🚀 第一步：準備並獲取最終 Headers
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                
                // 🚀 第二步：打印當前步驟標題與網址
                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);

                // 🚀 第三步：打印詳細請求頭（摺疊顯示）
                StringBuilder hdLog = new StringBuilder("<details style='margin:5px 0;'><summary style='color:#0077ff;font-size:11px;cursor:pointer;'>📤 點擊查看請求頭 (Headers)</summary><div style='color:#666;font-size:10px;padding:5px;background:#f9f9f9;border-left:2px solid #0077ff;margin-top:5px;'>");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    hdLog.append("<b>").append(entry.getKey()).append(":</b> ").append(entry.getValue()).append("<br>");
                }
                hdLog.append("</div></details>");
                logger(hdLog.toString());
                
                // 🚀 第四步：執行請求
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
                        logger("  └ 💡 提取變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }
            String finalUrl = replaceStepVars(play.optString("final_output", "{final_url}"));
            
            String result;
            // 🚀 1. 判斷 JSON 是否已經自定義了完整的返回格式
            if (finalUrl.trim().startsWith("{") && finalUrl.contains("\"parse\"")) {
                result = finalUrl;
            } else {
                // 🚀 2. 如果只是純網址，自動封裝標準格式
                JSONObject resJson = new JSONObject();
                resJson.put("parse", 0);
                resJson.put("url", finalUrl);
                
                // 添加默認 Header
                JSONObject headJson = new JSONObject();
                headJson.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
                resJson.put("header", headJson);
                
                result = resJson.toString();
            }

            logger("🏁 <b>[解析完成]</b> 返回: " + result);
            return result;

        } catch (Exception e) { 
            logger("🚨 [解析異常]: " + e.getMessage());
            // 🚀 3. 失敗兜底：帶上 UA 讓殼子嘗試內置解析
            return "{\"parse\":1,\"url\":\"" + id + "\",\"header\":{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\"}}"; 
        }
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
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String workRule = ruleStr.replace("@", "&&");
            if (workRule.contains("&&")) {
                String[] parts = workRule.split("&&");
                Element target = (root instanceof Document) ? ((Document) root).selectFirst(parts[0].trim()) : ((Element) root).selectFirst(parts[0].trim());
                if (target != null) {
                    String second = parts[1].trim();
                    if (isAttr(second)) return target.attr(second).trim();
                    if (second.equals("text") || second.isEmpty()) return target.text().trim();
                    return extractString(target.outerHtml(), second);
                }
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
            if (!ruleStr.contains("&&")) return content;
            String[] p = ruleStr.split("&&");
            int s = content.indexOf(p[0].trim());
            if (s == -1) return "";
            s += p[0].trim().length();
            int e = content.indexOf(p[1].trim(), s);
            return (e != -1) ? content.substring(s, e).trim() : "";
        } catch (Exception e) { return ""; }
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