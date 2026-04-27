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
    // 🚀 1. 刪除 ExecutorService 隊列，直接實時輸出
    private void logger(String msg) {
        try {
            // 不再排隊，操作到哪裡日誌就出到哪裡
            Proxy.log(msg);
        } catch (Exception e) {
            // 避免日誌報錯導致主程序卡死
        }
    }

    // 🚀 2. 暴力縮減預覽長度，解決緩衝區堵塞
    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | " + len + " 字符");
        
        if (showSource) {
            // 以前抓 7000 字太長了，現在縮到 500 字，反應速度提升 10 倍
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
            
            // --- 🚀 凱哥全能修復：【第一部分】線路與列表精準配對 ---
            String fromRule = rule.optString("dt_from");
            String listRule = rule.optString("dt_list");
            logger("🔍 [詳情診斷] 標題規則: " + fromRule + " | 列表規則: " + listRule);

            // 1. 兼容 [包含] 語法，提取真正的 CSS 標籤部分
            String cssFrom = fromRule;
            if (fromRule.contains("&&")) {
                String[] parts = fromRule.split("&&");
                // 💡 如果第一段是 [包含]，我們就取第二段作為 CSS 選擇器，否則取第一段
                cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
            }

            
            Elements fromElements = doc.select(cssFrom);
            logger("🔍 [詳情診斷] 找到標題數量: " + fromElements.size());

            List<String> fList = new ArrayList<>();
            List<String> pLists = new ArrayList<>();

            for (Element from : fromElements) {
                String sourceName = from.text().trim();
                if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (fromElements.indexOf(from) + 1);

                // 💡 凱哥雷達：精準定位標題附近的列表
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
                    logger("✅ [成功] 匹配到線路: [" + sourceName + "]");
                } else {
                    logger("❌ [失敗] 標題 [" + sourceName + "] 附近找不到符合規則的列表");
                }
            }

            // --- 🚀 凱哥全能修復：【第二部分】選集解析與日誌監控 ---
            List<String> playList = new ArrayList<>();
            logger("🔍 [詳情診斷] 準備解析播放列表，總線路數: " + pLists.size());

            for (int i = 0; i < pLists.size(); i++) {
                List<String> urls = new ArrayList<>();
                Document listDoc = Jsoup.parse(pLists.get(i));
                
                String nameRule = rule.optString("dt_list_name");
                String urlRule = rule.optString("dt_list_url");
                
                Elements aElements = listDoc.select("a");
                // 💡 這裡是關鍵日誌點
                logger("   📂 線路 " + (i+1) + " [" + fList.get(i) + "] 發現 <a> 標籤數量: " + aElements.size());

                for (Element a : aElements) {
                    String pName = extract(a, nameRule); 
                    String pUrl = extract(a, urlRule);
                    
                    if (!pName.isEmpty() && !pUrl.isEmpty()) {
                        urls.add(pName + "$" + pUrl);
                    }
                }
                
                if (urls.size() > 0) {
                    logger("   🎉 [成功] 提取到有效選集: " + urls.size() + " 個 (首集: " + urls.get(0).split("\\$")[0] + ")");
                } else {
                    logger("   ⚠️ [警告] 線路 " + (i+1) + " 沒能提取出有效選集，請檢查 dt_list_name/url 規則");
                }
                playList.add(TextUtils.join("#", urls));
            }

            // 最後存入對象
            vod.put("vod_play_from", TextUtils.join("$$$", fList));
            vod.put("vod_play_url", TextUtils.join("$$$", playList));

            // --- 🚀 替換結束 ---

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { 
            logger("🚨 [詳情崩潰]: " + e.getMessage());
            return ""; 
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 🚀 1. 預置原始地址，防止任何意外導致變量丟失
        String originalUrl = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
        
        try {
            logger("<br>🎬 <b>[播放解析啟動]</b>: " + originalUrl);

            // 🚀 2. 初始化變量池 (清空舊數據，放入起點地址)
            varPool.clear();
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl); 

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = (steps != null ? steps.length() : 0);

            // 🔥 核心保護：如果沒寫 Step，直接按原始邏輯走 (是流媒體就直連，不是就嗅探)
            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                int pValue = isStream ? 0 : 1;
                JSONObject res = new JSONObject();
                res.put("parse", pValue);
                res.put("url", originalUrl);
                res.put("header", getPlayHeaders(play));
                String result = res.toString();
                logger("<br>🏁 <b>[無步驟模式]</b> 返回: " + result);
                return result;
            }

            // 🚀 3. 核心 Step 循環：吸取老代碼精髓，實現「自動接力」
            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break; // 安全閥：最多 5 步

                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                
                // 📢 【接力點】：下一步請求的網址，優先從池子裡拿「上一步切出來的最新地址」
                // 如果 JSON 裡沒寫新 url，它就會拿 final_url 去請求
                String lastResult = varPool.get("final_url");
                String stepUrl = replaceStepVars(step.optString("url", lastResult));
                
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                logger("<b>Step " + (i + 1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);

                OkResult res = method.equals("post") 
                    ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers)
                    : OkHttp.get(stepUrl, null, headers);

                String html = res.getBody();
                logCheck("解析 Step " + (i + 1), html, true);

                // --- 變量提取與池子更新 ---
                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String vRule = vars.getString(k);
                        
                        // 執行提取 (支持 JSON 和 字符串截取)
                        String val = vRule.startsWith("json:") 
                            ? new JSONObject(html).optString(vRule.substring(5)) 
                            : extract(html, vRule);

                        if (!TextUtils.isEmpty(val)) {
                            varPool.put(k, val);
                            logger("  └ 💡 提取 [<b>" + k + "</b>] = " + val);
                            
                            // 🚀 【接力開關】：只要變量名包含 url 或符合 p1-p4，就認定它是下一步的目標
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val);
                                logger("  └ 🔄 <b>接力棒更新</b> -> 準備交給下一步");
                            }
                        }
                    }
                }
            }

            // --- 🚀 正常終點 ---
            String finalUrl = varPool.get("final_url");

            // 判定邏輯：只要地址變了（說明 Step 跑通了），或者包含流媒體格式，就給 0 (直連)
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalHasStream || !finalUrl.equals(originalUrl)) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", finalUrl);
            resJson.put("header", getPlayHeaders(play));

            String result = resJson.toString();
            
            // ✅ 正常完成時，用綠色顯示最終 JSON，讓凱哥一眼看到結果
            logger("<br>🏁 <b>[解析成功]</b> 推送 JSON:");
            logger("<code style='color:#00FF00;'>" + result + "</code>");
            
            return result;

        } catch (Exception e) {
            // --- 🚨 異常保底 ---
            // 這裡先把錯誤原因噴出來（紅色），方便凱哥查是哪一行崩了
            logger("<br>🚨 <b>[解析異常中斷]</b>: <span style='color:red;'>" + e.getMessage() + "</span>");
            
            JSONObject err = new JSONObject();
            try {
                // 崩潰時強制 parse: 1，讓殼子自己去嗅探原始地址
                err.put("parse", 1);
                err.put("url", originalUrl);
                err.put("header", getPlayHeaders(new JSONObject()));
            } catch (Exception ex) {
                // 這裡基本不會崩，除非 originalUrl 也是空的
            }
            
            String errResult = err.toString();
            
            // ✅ 即使崩潰了，也要把丟給殼子的保底 JSON 用紅色噴出來，防止盲目調試
            logger("⚠️ <b>[觸發保底推送]</b>:");
            logger("<code style='color:#FF0000;'>" + errResult + "</code>");
            
            return errResult;
        }
    } // 👈 這是 playerContent 方法的最末尾大括號


    // 🚀 配套的 Header 獲取方法（如果類末尾沒有就補上）
private JSONObject getPlayHeaders(JSONObject play) {
    // 🚀 從 JSON 規則中嘗試獲取自定義播放頭
    JSONObject headJson = play.optJSONObject("play_headers");

    // 🚀 如果規則沒寫，則使用凱哥強效保底頭部
    if (headJson == null) {
        headJson = new JSONObject();
        try {
            headJson.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
            headJson.put("Referer", this.siteUrl + "/");
            headJson.put("Origin", this.siteUrl); // 🔥 補強：解決部分站點 403 跨域問題
        } catch (Exception e) {
            // 靜默處理，確保不崩潰
        }
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
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            String finalResult = "";

            // 🚀 凱哥判定法：如果規則裡「不包含」&&，則認定為標準 CSS 規則，交給 Jsoup 處理
            if (!ruleStr.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    
                    // A1. 處理帶 @ 的屬性提取 (如 a@href)
                    if (ruleStr.contains("@")) {
                        String[] parts = ruleStr.split("@");
                        String selector = parts[0].trim();
                        String attr = parts[1].trim();
                        Element target = selector.isEmpty() ? el : el.selectFirst(selector);
                        finalResult = (target != null) ? target.attr(attr) : "";
                    } 
                    // A2. 處理不帶 @ 的純定位取文本 (如 span.absolute)
                    else {
                        Element target = el.selectFirst(ruleStr);
                        finalResult = (target != null) ? target.text() : "";
                    }
                }
                // logger("📡 [Jsoup 原生模式] 規則: " + ruleStr + " | 結果: " + finalResult);
            } 
            
            // 🚀 凱哥判定法：如果規則「包含」&&，則啟動全能工具 Java 進行切割
            else {
                String content = (root instanceof Document) ? ((Document) root).outerHtml() 
                               : (root instanceof Element) ? ((Element) root).outerHtml() 
                               : root.toString();

                KaiGeEngine.ExtractionResult res = 
                    KaiGeEngine.doExtract(content, ruleStr, this.siteUrl);
                
                finalResult = (res.value == null) ? "" : res.value;
                // logger("🔪 [工具 Java 模式] 規則: " + ruleStr + " | 結果: " + finalResult);
            }

            return finalResult;

        } catch (Exception e) {
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
