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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.List;
import java.net.URLEncoder;
import java.util.Map;
import java.util.*;
import java.util.Iterator;

public class KG extends Spider {
    private String siteUrl = "";
    private JSONObject rule = new JSONObject();

    // 播放解析臨時變量（playerContent 內部生命週期）
    private Map<String, String> varPool = new HashMap<>();

    // 全局變量緩存：跨 categoryContent / detailContent / playerContent 共享
    // key = 變量名（含後綴，如 token-c）
    // value = 緩存值
    private final Map<String, String> globalVarCache = new HashMap<>();

    // 最近一次請求的響應 HTML（供變量取值時默認使用）
    private volatile String lastHtml = "";

    private ExecutorService picExecutor = Executors.newFixedThreadPool(8);
    private final Map<String, String> picCache = Collections.synchronizedMap(new HashMap<>());

    // 預熱狀態位：整個實例生命週期只預熱一次
    private boolean siteWarmed = false;

    // ==================== 日誌工具 ====================

    /** 錯誤日誌：永遠打印 */
    private void logError(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    /** 調試日誌：只在 rule 裡 "debug": true 時打印 */
    private void logDebug(String msg) {
        if (!rule.optBoolean("debug", false)) return;
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private void logCheck(String title, String html) {
        if (TextUtils.isEmpty(html)) {
            logError("🚨 [网络请求失败] " + title + " 返回内容为空！请检查 UA、Referer 或网站是否开启了 CC 防护");
            return;
        }
        logDebug("📥 [" + title + "] 请求成功 | 收到 " + html.length() + " 字符");
    }

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) {
        try {
            logError("------------------------------------------");
            logError("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");

            if (TextUtils.isEmpty(extend)) {
                logError("🚨 [系統] 初始化失敗: 配置路徑為空");
                return;
            }

            String json;
            if (extend.startsWith("http")) {
                Map<String, String> initHeaders = new HashMap<>();
                initHeaders.put("Referer", "");
                OkResult res = OkHttp.get(extend, null, initHeaders);
                json = res.getBody();
            } else {
                json = extend;
            }

            if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) {
                logError("🚨 [系統] 初始化失敗: 讀取到的 JSON 格式不正確");
                return;
            }

            this.rule = new JSONObject(json);

            // 發布頁處理
            String indexUrl = rule.optString("index_url", "");
            if (!TextUtils.isEmpty(indexUrl)) {
                try {
                    logDebug("🔍 [发布页] 正在请求: " + indexUrl);
                    Map<String, String> indexHeaders = new HashMap<>();
                    indexHeaders.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
                    indexHeaders.put("Referer", "");

                    String realSite = "";

                    // 情况一：先尝试跟随 302 跳转
                    try {
                        Map<String, List<String>> locationHeaders = OkHttp.getLocationHeader(indexUrl, indexHeaders);
                        String realLocation = OkHttp.getLocation(locationHeaders);
                        if (!TextUtils.isEmpty(realLocation) && realLocation.startsWith("http")) {
                            java.net.URL realUrl = new java.net.URL(realLocation);
                            realSite = realUrl.getProtocol() + "://" + realUrl.getHost();
                            logDebug("✅ [发布页] 302跳转检测到真实域名: " + realSite);
                        }
                    } catch (Exception ignored) {}

                    // 情况二：302 没拿到，解析 HTML 提取链接
                    if (TextUtils.isEmpty(realSite)) {
                        try {
                            OkResult indexRes = KaiGeNet.smartRequest(indexUrl, "get", indexUrl, null, indexHeaders);
                            String indexHtml = indexRes.getBody();
                            if (!TextUtils.isEmpty(indexHtml)) {
                                String indexRule = rule.optString("index_rule", "");
                                String extracted = "";
                                if (!TextUtils.isEmpty(indexRule)) {
                                    extracted = KaiGeEngine.doExtract(indexHtml, indexRule, "").value;
                                    logDebug("🔍 [发布页] 使用自定义规则提取: " + extracted);
                                } else {
                                    String indexHost = new java.net.URL(indexUrl).getHost();
                                    Document indexDoc = Jsoup.parse(indexHtml);
                                    for (Element a : indexDoc.select("a[href]")) {
                                        String href = a.attr("abs:href");
                                        if (!TextUtils.isEmpty(href) && href.startsWith("http")) {
                                            String hrefHost = new java.net.URL(href).getHost();
                                            if (!hrefHost.equals(indexHost)) {
                                                extracted = href;
                                                logDebug("🔍 [发布页] 智能识别到跳转链接: " + extracted);
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (!TextUtils.isEmpty(extracted) && extracted.startsWith("http")) {
                                    java.net.URL realUrl = new java.net.URL(extracted);
                                    realSite = realUrl.getProtocol() + "://" + realUrl.getHost();
                                    logDebug("✅ [发布页] HTML提取真实域名: " + realSite);
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    if (!TextUtils.isEmpty(realSite)) {
                        rule.put("site_url", realSite);
                        rule.put("host", realSite);
                        logError("🌐 [发布页] 域名已更新为: " + realSite);
                    } else {
                        logDebug("⚠️ [发布页] 两种方式均未获取到域名，保持原域名");
                    }

                } catch (Exception ex) {
                    logError("⚠️ [发布页] 处理失败，保持原域名: " + ex.getMessage());
                }
            }

            // 從配置中自動提取域名（必须在 index_url 处理之后）
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));

            logError("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logError("🌐 [系統] 域名自動綁定: " + this.siteUrl);

            // 僅當規則明確開啟 cdndefend 時才觸發預熱和 CDN 盾檢測
            if (rule.optBoolean("cdndefend", false)) {
                try {
                    Map<String, List<String>> redirectHeaders = OkHttp.getLocationHeader(this.siteUrl, getHeaders(null));
                    String location = OkHttp.getLocation(redirectHeaders);
                    if (!TextUtils.isEmpty(location)) {
                        String locationHost = "";
                        try { locationHost = new java.net.URL(location).getHost(); } catch (Exception ignored) {}
                        String siteHost = "";
                        try { siteHost = new java.net.URL(this.siteUrl).getHost(); } catch (Exception ignored) {}
                        if (!locationHost.equals(siteHost)) {
                            logError("<span style='color:#f1c40f;'>⚠️ [预热] 跨域跳转已拒绝: </span>" + location);
                            throw new Exception("cross domain redirect blocked");
                        }
                    }
                    String redirectCookie = "";
                    if (redirectHeaders != null) {
                        List<String> cookies = redirectHeaders.get("Set-Cookie");
                        if (cookies == null) cookies = redirectHeaders.get("set-cookie");
                        if (cookies != null && !cookies.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (String c : cookies) {
                                String part = c.split(";")[0].trim();
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(part);
                            }
                            redirectCookie = sb.toString();
                        }
                    }
                    if (!TextUtils.isEmpty(redirectCookie)) {
                        JSONObject hdrs = rule.optJSONObject("headers");
                        if (hdrs == null) hdrs = new JSONObject();
                        String existCookie = hdrs.optString("Cookie", "");
                        hdrs.put("Cookie", TextUtils.isEmpty(existCookie) ? redirectCookie : existCookie + "; " + redirectCookie);
                        rule.put("headers", hdrs);
                        KaiGeNet.putCookie(this.siteUrl, redirectCookie);
                        logError("<span style='color:#2ecc71;'>🍪 [302Token] cookie成功: </span>" + redirectCookie);
                    }
                    // 带cookie预热一次，处理CDN盾
                    String homeHtml = KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null)).getBody();
                    if (!TextUtils.isEmpty(homeHtml) && homeHtml.contains("cdndefend_js_cookie")) {
                        String cookie = KaiGeNet.cdnDefendCookie(homeHtml);
                        if (!TextUtils.isEmpty(cookie)) {
                            JSONObject hdrs = rule.optJSONObject("headers");
                            if (hdrs == null) hdrs = new JSONObject();
                            hdrs.put("Cookie", cookie);
                            rule.put("headers", hdrs);
                            logError("<span style='color:#2ecc71;'>🍪 [CDN盾] 自动计算cookie成功: </span>" + cookie);
                        }
                    }
                    logError("<span style='color:#2ecc71;'>✅ [首页预热] 完成</span>");
                } catch (Exception ex) {
                    logError("<span style='color:#f1c40f;'>⚠️ [首页预热] 异常: </span>" + ex.getMessage());
                }
            }

        } catch (Exception e) {
            logError("🚨 [系統] 初始化崩潰: " + e.getMessage());
        }
    }

    // ==================== 主頁 ====================

    @Override
    public String homeContent(boolean filter) {
        try {
            logDebug("🏠 [主頁] 正在加載分類導航...");
            JSONArray classes = rule.optJSONArray("classes");

            if (classes == null || classes.length() == 0) {
                logError("🚨 [主頁] 警告：JSON 規則中未定義 classes 或格式錯誤");
                return "";
            }

            JSONArray resultClasses = new JSONArray();
            for (int i = 0; i < classes.length(); i++) {
                JSONObject oldCate = classes.getJSONObject(i);
                JSONObject newCate = new JSONObject();
                String name = oldCate.optString("type_name", oldCate.optString("name"));
                String id   = oldCate.optString("type_id",   oldCate.optString("id"));
                newCate.put("type_name", name);
                newCate.put("type_id",   id);
                resultClasses.put(newCate);
            }

            logDebug("✅ [主頁] 分類加載成功，共 " + resultClasses.length() + " 個頻道");

            JSONObject result = new JSONObject();
            result.put("class", resultClasses);
            result.put("list",  new JSONArray());
            if (rule.has("filters")) {
                result.put("filters", rule.optJSONObject("filters"));
            }
            return result.toString();
        } catch (Exception e) {
            logError("🚨 [主頁異常]: " + e.getMessage());
            return "";
        }
    }

    // ==================== 分類 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String method = rule.optString("cate_method", "get").toLowerCase();
            String url    = (pg.equals("1") && rule.has("cate_page_1")) ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String body   = rule.optString("cate_body", "");

            // 確定最終 TID
            String rTid = tid;
            if (e != null) {
                if (e.containsKey("tid"))     rTid = e.get("tid");
                else if (e.containsKey("type_id")) rTid = e.get("type_id");
            }

            // 基礎變量與篩選變量替換
            url = url.replace("{tid}", URLEncoder.encode(rTid, "UTF-8")).replace("{pg}", pg);
            if (!TextUtils.isEmpty(body)) body = body.replace("{tid}", rTid).replace("{pg}", pg);

            String[] filterKeys = {"area", "class", "year", "by", "lang", "letter", "字母"};
            for (String key : filterKeys) {
                String val = (e != null && e.containsKey(key)) ? e.get(key) : "";
                url = url.replace("{" + key + "}", URLEncoder.encode(val, "UTF-8"));
                if (!TextUtils.isEmpty(body)) body = body.replace("{" + key + "}", val);
            }

            if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            logDebug("<b style='color:#2ecc71;'>📂 [分類啟動]</b> " + url);
            if (method.equals("post") && !TextUtils.isEmpty(body)) {
                logDebug("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // 首次 pg=1 時預熱一次（整個實例生命週期只跑一次）
            if (pg.equals("1") && !siteWarmed) {
                KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null));
                siteWarmed = true;
                logDebug("✅ [預熱] 完成");
            }

            // 解析全局變量（分類作用域）
            resolveGlobalVars("type");

            // 正式發起請求
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            if (!TextUtils.isEmpty(html)) lastHtml = html;

            logCheck("分類", html);

            if (rule.optBoolean("debug", false) && !TextUtils.isEmpty(html)) {
                String itemRule = rule.optString("cate_item");
                if (!TextUtils.isEmpty(itemRule)) {
                    Document doc = Jsoup.parse(html);
                    Elements items = doc.select(itemRule);
                    if (!items.isEmpty()) {
                        logDebug("<b style='color:#2ecc71;'>✅ [定位層成功] 匹配到項目数量: " + items.size() + "</b>");
                    } else {
                        logDebug("<b style='color:red;'>❌ [定位層錯誤] 規則 [" + itemRule + "] 找不到內容，請修改 cate_item！</b>");
                    }
                }
            }

            // 基礎重試補償
            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                logDebug("<b style='color:#f1c40f;'>⚠️ 內容異常，嘗試二次刷新...</b>");
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                res  = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
                html = res.getBody();
            }

            return parseList(html, pg, false);
        } catch (Exception ex) {
            logError("<b style='color:red;'>🚨 [分類異常]:</b> " + ex.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String method = rule.optString("search_method", "get").toLowerCase();
            String url    = rule.optString("search_url");
            String body   = rule.optString("search_body", "");

            if (method.equals("post")) {
                body = body.replace("{wd}", key);
            } else {
                url = url.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            }

            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            logDebug("<b style='color:#3498db;'>🔍 [搜索啟動]</b> 方法: " + method.toUpperCase());
            logDebug("<span style='color:#9b59b6;'>[搜索網址]</span> " + url);
            if (method.equals("post")) logDebug("<span style='color:#f1c40f;'>[POST參數]</span> " + body);

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            logCheck("搜索", res.getBody());
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) {
            logError("<b style='color:red;'>🚨 [搜索異常]:</b> " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ==================== 詳情 ====================

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id     = ids.get(0);
            String method = rule.optString("detail_method", "get").toLowerCase();
            String url    = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            String body   = rule.optString("detail_body", "");

            if (method.equals("post")) {
                body = body.replace("{id}", id);
            } else {
                if (url.contains("{id}")) {
                    url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));
                }
            }

            logDebug("<b style='color:#f39c12;'>📋 [詳情啟動]</b> 方法: " + method.toUpperCase() + " | ID: " + id);
            if (method.equals("post")) logDebug("<span style='color:#f1c40f;'>[POST參數]</span> " + body);

            OkResult res  = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html   = res.getBody();
            logCheck("詳情", html);

            // 若詳情接口返回標準蘋果 CMS JSON，直接解析
            if (html != null && html.trim().startsWith("{")) {
                try {
                    JSONObject json     = new JSONObject(html.trim());
                    JSONArray dataList  = json.optJSONArray("list");
                    if (dataList != null && dataList.length() > 0) {
                        JSONObject item = dataList.getJSONObject(0);
                        JSONObject vod  = new JSONObject();
                        vod.put("vod_id",        ids.get(0));
                        vod.put("vod_name",      item.optString("vod_name",      item.optString("name",      "")));
                        vod.put("vod_pic",       item.optString("vod_pic",       item.optString("pic",       "")));
                        vod.put("vod_remarks",   item.optString("vod_remarks",   item.optString("remarks",   "")));
                        vod.put("vod_actor",     item.optString("vod_actor",     item.optString("actor",     "")));
                        vod.put("vod_director",  item.optString("vod_director",  item.optString("director",  "")));
                        vod.put("vod_content",   item.optString("vod_content",   item.optString("content",   "")));
                        vod.put("vod_play_from", item.optString("vod_play_from", ""));
                        vod.put("vod_play_url",  item.optString("vod_play_url",  ""));
                        logDebug("<b style='color:#2ecc71;'>✅ [详情] JSON直解成功: </b>" + vod.optString("vod_name"));
                        varPool.put("vod_name", vod.optString("vod_name", "未知标题"));
                        String playUrl = vod.optString("vod_play_url", "");
                        if (!TextUtils.isEmpty(playUrl)) {
                            varPool.put("vod_total_episode", String.valueOf(playUrl.split("#").length));
                            varPool.put("vod_play_url", playUrl);
                        }
                        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
                    }
                } catch (Exception ex) {
                    logDebug("<b style='color:red;'>❌ [详情] JSON解析失败: </b>" + ex.getMessage());
                }
            }

            // HTML 解析
            Document doc      = Jsoup.parse(html);
            JSONObject smartVod = KaiGeSmart.parseDetail(html);
            JSONObject vod    = new JSONObject();
            vod.put("vod_id", id);

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

            if (TextUtils.isEmpty(rule.optString("dt_list"))) {
                vod.put("vod_play_from", smartVod.optString("vod_play_from"));
                vod.put("vod_play_url",  smartVod.optString("vod_play_url"));
            } else {
                processOriginalDetail(doc, vod);
            }

            varPool.put("vod_name", vod.optString("vod_name", "未知标题"));
            String playUrl = vod.optString("vod_play_url", "");
            if (!TextUtils.isEmpty(playUrl)) {
                varPool.put("vod_total_episode", String.valueOf(playUrl.split("#").length));
                varPool.put("vod_play_url", playUrl);
            }
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) {
            logError("<b style='color:red;'>🚨 [詳情異常]:</b> " + e.getMessage());
            return "";
        }
    }

    // 原有詳情解析邏輯
    private void processOriginalDetail(Document doc, JSONObject vod) throws Exception {
        String fromRule = rule.optString("dt_from");
        String listRule = rule.optString("dt_list");
        String cssFrom  = fromRule;
        if (fromRule.contains("&&")) {
            String[] parts = fromRule.split("&&");
            cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
        }

        logDebug("🔍 [調試] dt_from 規則: " + cssFrom);
        logDebug("🔍 [調試] dt_list 規則: " + listRule);

        Elements fromElements = doc.select(cssFrom);
        Elements allLists     = doc.select(listRule);

        logDebug("🔍 [調試] dt_list 匹配到列表数: " + allLists.size());

        List<String> fList    = new ArrayList<>();
        List<String> pLists   = new ArrayList<>();

        // 用顯式計數器替代 indexOf，避免 O(n) 性能問題
        int idx = 0;
        for (Element from : fromElements) {
            String sourceName = from.text().trim();
            if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (idx + 1);

            Element nextList = (idx < allLists.size()) ? allLists.get(idx) : null;
            if (nextList != null) {
                logDebug("🔍 [pLists存入] 第" + fList.size() + "条线路 HTML前50: " + nextList.outerHtml().substring(0, Math.min(50, nextList.outerHtml().length())));
                fList.add(sourceName);
                pLists.add(nextList.outerHtml());
            }
            idx++;
        }

        List<String> playList = new ArrayList<>();
        for (int i = 0; i < pLists.size(); i++) {
            List<String> urls = new ArrayList<>();
            Document listDoc  = Jsoup.parse(pLists.get(i));
            Elements aElements = listDoc.select("a");
            for (Element a : aElements) {
                String pName = a.text().trim();
                String pUrl  = a.attr("href").trim();
                if (!pName.isEmpty() && !pUrl.isEmpty() && !pUrl.contains("javascript")) {
                    urls.add(pName + "$" + pUrl);
                }
            }
            playList.add(TextUtils.join("#", urls));
        }

        vod.put("vod_play_from", TextUtils.join("$$$", fList));
        vod.put("vod_play_url",  TextUtils.join("$$$", playList));

        logDebug("🔍 [最終組裝] from: " + vod.optString("vod_play_from") + " | url前100: " + vod.optString("vod_play_url").substring(0, Math.min(100, vod.optString("vod_play_url").length())));
    }

    // ==================== 播放解析 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String originalUrl = id.startsWith("/") && !id.startsWith("//") ? this.siteUrl + id : id;
        try {
            logDebug("<b style='color:#e74c3c;'>🎬 [播放解析啟動]</b> 原始ID: " + originalUrl);

            // 備份弹幕數據（必须在 clear 之前）
            String danmuTitle   = varPool.get("vod_name");
            String danmuPlayUrl = varPool.get("vod_play_url");

            varPool.clear();
            if (!TextUtils.isEmpty(danmuTitle))   varPool.put("vod_name",    danmuTitle);
            if (!TextUtils.isEmpty(danmuPlayUrl)) varPool.put("vod_play_url", danmuPlayUrl);

            // 从播放链接反查集数
            String currentEpisode = "1";
            if (!TextUtils.isEmpty(danmuPlayUrl)) {
                String[] episodes = danmuPlayUrl.split("#");
                logDebug("🔍 [弹幕集数] 共 " + episodes.length + " 集，正在匹配 id=" + id);
                String cleanId = id.contains("?") ? id.split("\\?")[0] : id;
                for (int i = 0; i < episodes.length; i++) {
                    String[] parts    = episodes[i].split("\\$");
                    String epUrl      = parts.length > 1 ? parts[parts.length - 1].trim() : "";
                    String cleanEpUrl = epUrl.contains("?") ? epUrl.split("\\?")[0] : epUrl;
                    if (!epUrl.isEmpty() && (epUrl.equals(id) || cleanEpUrl.equals(cleanId) || epUrl.contains(cleanId) || cleanId.contains(cleanEpUrl))) {
                        String epName = parts[0].replaceAll("[^0-9]", "");
                        currentEpisode = TextUtils.isEmpty(epName) ? String.valueOf(i + 1) : epName;
                        logDebug("✅ [弹幕集数] 匹配成功！第 " + currentEpisode + " 集，集名原文=" + parts[0]);
                        break;
                    }
                    if (i == episodes.length - 1) {
                        logDebug("⚠️ [弹幕集数] 全部 " + episodes.length + " 集均未匹配，cleanId=" + cleanId);
                    }
                }
            }

            if (!TextUtils.isEmpty(danmuTitle)) varPool.put("vod_name", danmuTitle);
            varPool.put("vod_episode", currentEpisode);
            logDebug("<span style='color:#9b59b6;'>[弹幕] 标题=" + varPool.get("vod_name") + ", 集数=" + currentEpisode + "</span>");

            varPool.put("play_id",  originalUrl);
            varPool.put("final_url", originalUrl);

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount   = (steps != null ? steps.length() : 0);
            boolean finalStepSuccess = false;

            // jx 动态解析配置
            String jx = play.optString("jx", "");
            if (!TextUtils.isEmpty(jx)) {
                try {
                    logDebug("<span style='color:#9b59b6;'>[jx配置] 请求: </span>" + jx);
                    OkResult cfgRes  = KaiGeNet.smartRequest(this.siteUrl, "get", jx, null, getHeaders(null));
                    String cfgBody   = cfgRes.getBody();

                    if (!TextUtils.isEmpty(cfgBody)) {
                        String jxList  = play.optString("jx_list",  "");
                        String jxTitle = play.optString("jx_title", "");
                        String jxParse = play.optString("jx_parse", "");

                        if (!TextUtils.isEmpty(jxList) && !TextUtils.isEmpty(jxTitle) && !TextUtils.isEmpty(jxParse)) {
                            String block = "";
                            if (jxList.contains(".") && !jxList.contains("&&")) {
                                try {
                                    JSONObject cfgJson = new JSONObject(cfgBody);
                                    Object pathResult  = getJsonByPath(cfgJson, jxList);
                                    block = pathResult != null ? pathResult.toString() : "";
                                } catch (Exception ignored) {}
                            } else {
                                block = KaiGeEngine.doExtract(cfgBody, jxList, this.siteUrl).value;
                            }
                            logDebug("<span style='color:#3498db;'>[jx配置] 切出片段长度: </span>" + block.length());

                            String val = "";
                            try {
                                JSONArray jxArray = new JSONArray(block);
                                for (int j = 0; j < jxArray.length(); j++) {
                                    JSONObject entry = jxArray.getJSONObject(j);
                                    if (flag.equals(entry.optString(jxTitle))) {
                                        val = entry.optString(jxParse, "");
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                                String titleRule = "\"" + jxTitle + "\":\"" + flag + "\"&&\"" + jxParse + "\":\"&&\"";
                                val = KaiGeEngine.doExtract(block, titleRule, this.siteUrl).value;
                            }
                            val = val.replace("\\/", "/").replace("\\", "").trim();
                            varPool.put("jx_parse", val);
                            if (!TextUtils.isEmpty(val)) {
                                logDebug("<span style='color:#2ecc71;'>[jx配置] 命中 [" + flag + "] jx_parse = </span>" + val);
                            } else {
                                logDebug("<span style='color:#f1c40f;'>[jx配置] 线路 [" + flag + "] 无解析前缀，视为直链</span>");
                            }
                        }
                    }
                } catch (Exception ex) {
                    logError("<b style='color:red;'>[jx配置] 失败: </b>" + ex.getMessage());
                }
            }

            // 無解析步驟，直接推送原始地址
            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject res = new JSONObject();
                res.put("parse",  isStream ? 0 : 1);
                res.put("url",    originalUrl);
                res.put("header", getPlayHeaders(play));
                injectDanmaku(res, play);
                logDebug("<b style='color:#2ecc71;'>🚀 [Direct] 無解析步驟，直接推送原始地址</b>");
                return res.toString();
            }

            // 執行解析步驟（無上限）
            for (int i = 0; i < stepCount; i++) {
                JSONObject step = steps.getJSONObject(i);
                String stepUrl  = replaceStepVars(step.optString("url", varPool.containsKey("final_url") ? varPool.get("final_url") : ""));
                String method   = step.optString("method", "get");

                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                logDebug("<span style='color:#3498db;'>[Step " + (i + 1) + " 請求]</span> " + method.toUpperCase() + " -> " + stepUrl);
                logDebug("<span style='color:#9b59b6;'>[請求頭查看]</span> " + headers.toString());

                OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl, replaceStepVars(step.optString("body")), getHeaders(step.optJSONObject("headers")));
                String html  = res.getBody();

                if (TextUtils.isEmpty(html)) {
                    logError("<b style='color:red;'>❌ [Step " + (i + 1) + "] 返回內容完全為空！</b>");
                } else {
                    if (rule.optBoolean("debug", false)) {
                        String preview = (html.length() > 500 ? html.substring(0, 500) : html)
                            .trim().replace("\n", " ").replace("\r", " ");
                        logDebug("<div style='background:#2c3e50; color:#ecf0f1; padding:5px; border-left:5px solid #e74c3c;'>Step " + (i + 1) + " 源碼預覽: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...</div>");
                    }
                }

                // 變量提取
                JSONObject vars = step.optJSONObject("vars");
                if (vars != null) {
                    boolean currentStepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k      = it.next();
                        String vRule  = vars.optString(k).trim();
                        String val    = "";

                        String[] vRules = vRule.split("\\|\\|");
                        for (String singleRule : vRules) {
                            singleRule = singleRule.trim();
                            if (singleRule.startsWith("json:")) {
                                try {
                                    String keyName = singleRule.substring(5).trim();
                                    JSONObject jsonObj = new JSONObject(html.trim());
                                    val = jsonObj.optString(keyName);
                                } catch (Exception ex) {
                                    val = "";
                                }
                            } else {
                                val = KaiGeEngine.doExtract(html, singleRule, this.siteUrl).value;
                            }
                            if (!TextUtils.isEmpty(val)) {
                                logDebug("    └─ <span style='color:#f1c40f;'>[提取成功]</span> 命中規則: " + singleRule);
                                break;
                            } else {
                                logDebug("    └─ <span style='color:#95a5a6;'>⚠️ [規則未命中]</span> 嘗試下一條: " + singleRule);
                            }
                        }

                        if (!TextUtils.isEmpty(val)) {
                            val = val.replace("\\/", "/").replace("\\", "").trim();
                            varPool.put(k, val);
                            logDebug("    └─ <span style='color:#f1c40f;'>[提取成功]</span> " + k + " = " + (val.length() > 80 ? val.substring(0, 80) + "..." : val));
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val);
                                currentStepAnyOk = true;
                            }
                        } else {
                            logDebug("    └─ <b style='color:#95a5a6;'>⚠️ [提取為空]</b> 鍵: " + k + " | 規則: " + vRule);
                        }
                    }
                    if (i == stepCount - 1 && currentStepAnyOk) finalStepSuccess = true;
                }
            }

            String finalUrl = (varPool.containsKey("final_url") ? varPool.get("final_url") : "").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalStepSuccess || finalHasStream) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse",  pValue);
            resJson.put("url",    (pValue == 0) ? finalUrl : originalUrl);
            resJson.put("header", getPlayHeaders(play));
            injectDanmaku(resJson, play);

            logDebug("<b style='color:#2ecc71;'>🚀 [Final:推送 JSON]</b>");
            logDebug("<div style='background:#1a1a1a; color:#00ff00; padding:8px; border:1px solid #2ecc71; font-family:monospace;'>" + resJson.toString() + "</div>");

            return resJson.toString();
        } catch (Exception e) {
            logError("<b style='color:red;'>❌ [播放解析崩潰]:</b> " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + originalUrl + "\",\"header\":{}}";
        }
    }

    // 弹幕注入（抽成私有方法，避免重複代碼）
    private void injectDanmaku(JSONObject res, JSONObject play) {
        if (!rule.optBoolean("danmaku", false)) return;
        String title = varPool.containsKey("vod_name")    ? varPool.get("vod_name")    : "";
        String ep    = varPool.containsKey("vod_episode") ? varPool.get("vod_episode") : "1";
        if (TextUtils.isEmpty(title)) return;
        try {
            String danmakuUrl = "http://127.0.0.1:10086/danmu"
                + "?title="   + URLEncoder.encode(title, "UTF-8")
                + "&episode=" + URLEncoder.encode(TextUtils.isEmpty(ep) ? "1" : ep, "UTF-8");
            res.put("danmaku", danmakuUrl);
            logDebug("<b style='color:#2ecc71;'>💬 [弹幕] 已注入 danmaku=" + danmakuUrl + "</b>");
        } catch (Exception e) {
            logError("<b style='color:red;'>❌ [弹幕] 注入失败: " + e.getMessage() + "</b>");
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

    // ==================== 列表解析 ====================

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            JSONArray list = new JSONArray();
            String prefix  = isSearch ? "sc_" : "cate_";
            String detailTemplate = rule.optString("detail_url", "");

            String itemRule   = rule.optString(prefix + "item",    rule.optString("cate_item",    ""));
            String idRule     = rule.optString(prefix + "id",      rule.optString("cate_id",      ""));
            String nameRule   = rule.optString(prefix + "name",    rule.optString("cate_name",    ""));
            String picRule    = rule.optString(prefix + "pic",     rule.optString("cate_pic",     ""));
            String remarkRule = rule.optString(prefix + "remarks", rule.optString("cate_remarks", ""));

            // json: 模式
            if (itemRule.toLowerCase().startsWith("json:")) {
                String arrayKey = itemRule.substring(5).trim();
                JSONObject json = new JSONObject(html);
                JSONArray array = json.optJSONArray(arrayKey);
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        JSONObject vod  = new JSONObject();

                        String vId      = item.optString(stripJson(idRule),      item.optString("vod_id", item.optString("id", "")));
                        String vName    = item.optString(stripJson(nameRule),    item.optString("vod_name", item.optString("name", "")));
                        String vPic     = item.optString(stripJson(picRule),     item.optString("vod_pic", item.optString("pic", "")));
                        String vRemarks = item.optString(stripJson(remarkRule),  item.optString("vod_remarks", item.optString("remarks", "")));

                        if (TextUtils.isEmpty(vId) || TextUtils.isEmpty(vName)) continue;

                        if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                            vod.put("vod_id", detailTemplate.replace("{id}", vId));
                        } else {
                            vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                        vod.put("vod_name",    vName);
                        vod.put("vod_pic",     fixPicUrl(vPic));
                        vod.put("vod_remarks", vRemarks);
                        if (vod.has("vod_id")) list.put(vod);
                    }
                }

            // 标准 JSON 对象（自动兼容苹果CMS）
            } else if (html != null && html.trim().startsWith("{")) {
                JSONObject json    = new JSONObject(html);
                String listPath    = rule.optString("cate_list_path", "list");
                Object pathResult  = getJsonByPath(json, listPath);
                JSONArray array    = pathResult instanceof JSONArray ? (JSONArray) pathResult : null;
                if (array == null) array = json.optJSONArray("list");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject vod = KaiGeSmart.parseListItem(array.getJSONObject(i));
                        if (!vod.has("vod_id")) continue;
                        String vId = vod.optString("vod_id");
                        if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                            vod.put("vod_id", detailTemplate.replace("{id}", vId));
                        } else {
                            vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                        list.put(vod);
                    }
                }

            // 直接 JSON 数组（如接口返回 [{...},{...}]）
            } else if (html != null && html.trim().startsWith("[")) {
                JSONArray array = new JSONArray(html.trim());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject vod = KaiGeSmart.parseListItem(array.getJSONObject(i));
                    if (!vod.has("vod_id")) continue;
                    String vId = vod.optString("vod_id");
                    if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                        vod.put("vod_id", detailTemplate.replace("{id}", vId));
                    } else {
                        vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                    }
                    list.put(vod);
                }

            // HTML CSS 选择器
            } else {
                Document doc   = Jsoup.parse(html);
                Elements items = doc.select(itemRule);

                for (Element item : items) {
                    JSONObject smartVod = KaiGeSmart.parseList(item);
                    JSONObject vod      = new JSONObject();

                    String vId = extract(item, idRule);
                    if (TextUtils.isEmpty(vId)) vId = smartVod.optString("vod_id");

                    if (!TextUtils.isEmpty(vId)) {
                        if (isSearch && !detailTemplate.isEmpty() && !vId.startsWith("http")) {
                            vod.put("vod_id", detailTemplate.replace("{id}", vId));
                        } else {
                            vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                    }

                    String vName = extract(item, nameRule);
                    vod.put("vod_name", TextUtils.isEmpty(vName) ? smartVod.optString("vod_name") : vName);

                    String vPic = extract(item, picRule);
                    if (TextUtils.isEmpty(vPic)) vPic = smartVod.optString("vod_pic");
                    vod.put("vod_pic", fixPicUrl(vPic));

                    String vRemarks = extract(item, remarkRule);
                    vod.put("vod_remarks", TextUtils.isEmpty(vRemarks) ? smartVod.optString("vod_remarks") : vRemarks);

                    if (vod.has("vod_id")) list.put(vod);
                }
            }

            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) {
            logError("🚨 [parseList 異常]: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ==================== 圖片處理 ====================

    private String fixPicUrl(String pic) {
        if (TextUtils.isEmpty(pic)) return pic;

        // 缓存命中直接返回
        if (picCache.containsKey(pic)) return picCache.get(pic);

        // pic_jump 需要明確開啟才走二次提取邏輯
        if (rule.optBoolean("pic_jump", false)) {
            String prefix      = rule.optString("pic_prefix", "");
            String suffix      = rule.optString("pic_suffix", "");
            String extractRule = rule.optString("pic_extract", "");

            if (!TextUtils.isEmpty(prefix)) {
                String apiUrl = prefix + pic + suffix;
                try {
                    Future<String> future = picExecutor.submit(() -> {
                        try {
                            OkResult res   = KaiGeNet.smartRequest(this.siteUrl, "get", apiUrl, null, getHeaders(null));
                            String response = res.getBody();

                            if (!TextUtils.isEmpty(response) && !TextUtils.isEmpty(extractRule)) {
                                String finalPic = KaiGeEngine.doExtract(response, extractRule, "").value;
                                if (!TextUtils.isEmpty(finalPic)) {
                                    String picHost = rule.optString("pic_host", "");
                                    if (!finalPic.startsWith("http") && !TextUtils.isEmpty(picHost)) {
                                        finalPic = picHost + (finalPic.startsWith("/") ? "" : "/") + finalPic;
                                    }
                                    return finalPic;
                                }
                            }
                        } catch (Exception e) {
                            logError("⚠️ [pic_jump] 请求失败: " + e.getMessage());
                        }
                        return pic;
                    });

                    String result = future.get(8, TimeUnit.SECONDS);
                    picCache.put(pic, result);
                    return result;
                } catch (Exception e) {
                    logError("⚠️ [pic_jump] 多线程超时或异常: " + e.getMessage());
                }
            }
        }

        // 普通模式
        if (pic.startsWith("http://") || pic.startsWith("https://")) {
            picCache.put(pic, pic);
            return pic;
        }
        if (pic.startsWith("//")) {
            String result = "https:" + pic;
            picCache.put(pic, result);
            return result;
        }

        String picHost = rule.optString("pic_host", this.siteUrl);
        String result  = picHost + (pic.startsWith("/") ? "" : "/") + pic;
        picCache.put(pic, result);
        return result;
    }

    // ==================== 彈幕 ====================

    public String danmaku(String url) throws Exception {
        String title   = varPool.containsKey("vod_name")    ? varPool.get("vod_name")    : "";
        String episode = varPool.containsKey("vod_episode") ? varPool.get("vod_episode") : "";

        if (TextUtils.isEmpty(episode) && url.contains("$")) {
            String epPart = url.split("\\$")[0];
            episode = epPart.replaceAll("[^0-9]", "");
        }

        if (TextUtils.isEmpty(title))   title   = "未知标题";
        if (TextUtils.isEmpty(episode)) episode = "1";

        return Proxy.getUrl() + "?do=danmu&title="   + URLEncoder.encode(title, "UTF-8")
                              + "&episode=" + URLEncoder.encode(episode, "UTF-8");
    }

    // ==================== 工具方法 ====================

    private String stripJson(String r) {
        if (TextUtils.isEmpty(r)) return "";
        String s = r.trim();
        if (s.toLowerCase().startsWith("json:")) return s.substring(5).trim();
        return s;
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String realRule = replaceStepVars(ruleStr);
            if (!realRule.contains("&&") && !realRule.startsWith("j:") && !realRule.startsWith("p:")) {
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
            }
            String content = (root instanceof Document) ? ((Document) root).outerHtml()
                : (root instanceof Element) ? ((Element) root).outerHtml()
                : root.toString();
            return KaiGeEngine.doExtract(content, realRule, this.siteUrl, globalVarCache, lastHtml, rule).value;
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        // 先替換 varPool（播放臨時變量）
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        // 再替換 globalVarCache（全局變量，帶 {{}} 格式）
        res = KaiGeEngine.replaceVars(res, globalVarCache);
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

    private Object getJsonByPath(JSONObject json, String path) {
        try {
            Object current = json;
            for (String key : path.split("\\.")) {
                if (current instanceof JSONObject) current = ((JSONObject) current).opt(key);
                else return null;
            }
            return current;
        } catch (Exception e) { return null; }
    }

    // ==================== 全局變量系統 ====================

    /**
     * 解析全局變量：掃描 rule JSON，找出所有帶變量後綴的字段
     * 後綴規則（與 XBPQ 一致）：
     *   -c  全局緩存，有值不重取
     *   -b  備用緩存，優先取新值，失敗用緩存
     *   普通字段（用到 {{xxx}} 時按需取值）
     *
     * @param scope 當前作用域：type（分類）/ detail（詳情）/ play（播放）
     */
    private void resolveGlobalVars(String scope) {
        try {
            Iterator<String> keys = rule.keys();
            while (keys.hasNext()) {
                String fieldName = keys.next();
                // 跳過非變量字段（變量字段名含有 - 且不是已知功能字段）
                if (!isVarField(fieldName)) continue;

                String baseName = getVarBaseName(fieldName);
                boolean isCache = fieldName.contains("-c");
                boolean isBuffer = fieldName.contains("-b");

                // -c 有緩存就跳過
                if (isCache && globalVarCache.containsKey(baseName) && !TextUtils.isEmpty(globalVarCache.get(baseName))) {
                    continue;
                }

                // 取新值
                String varRule = rule.optString(fieldName, "");
                if (TextUtils.isEmpty(varRule)) continue;

                String newVal = fetchVarValue(varRule);

                if (!TextUtils.isEmpty(newVal)) {
                    globalVarCache.put(baseName, newVal);
                    logDebug("🔑 [全局變量] " + baseName + " = " + (newVal.length() > 60 ? newVal.substring(0, 60) + "..." : newVal));
                } else if (isBuffer && globalVarCache.containsKey(baseName)) {
                    logDebug("🔑 [全局變量-b] " + baseName + " 取新值失敗，使用緩存");
                } else if (!isCache && !isBuffer) {
                    // 普通臨時變量每次都更新
                    globalVarCache.put(baseName, newVal);
                }
            }
        } catch (Exception e) {
            logError("⚠️ [全局變量] 解析異常: " + e.getMessage());
        }
    }

    /**
     * 按需取單個變量的值（用於 {{變量名}} 懶求值）
     * 先查緩存，緩存沒有才執行規則
     */
    private String getVar(String varName) {
        // 先查精確名
        if (globalVarCache.containsKey(varName)) return globalVarCache.get(varName);

        // 在 rule 裡找帶後綴的字段
        String[] suffixes = {"-c", "-b", "-t", "-d", "-p", "-u", "-h"};
        for (String suffix : suffixes) {
            String fieldName = varName + suffix;
            if (rule.has(fieldName)) {
                String varRule = rule.optString(fieldName, "");
                String val = fetchVarValue(varRule);
                if (!TextUtils.isEmpty(val)) globalVarCache.put(varName, val);
                return val;
            }
        }
        // 無後綴直接字段
        if (rule.has(varName)) {
            String varRule = rule.optString(varName, "");
            // 如果是截取規則，執行它；如果是固定值，直接返回
            if (varRule.contains("&&") || varRule.startsWith("j:") || varRule.startsWith("p:") || varRule.startsWith("url:")) {
                return fetchVarValue(varRule);
            }
            return varRule;
        }
        return "";
    }

    /**
     * 執行變量的取值規則
     * 支持：
     *   url:http...$sub:a&&b   — 請求指定網頁後截取
     *   a&&b                   — 從 lastHtml 截取
     *   j:data.token           — 從 lastHtml JSON 截取
     *   固定字符串             — 直接返回
     */
    private String fetchVarValue(String varRule) {
        try {
            if (TextUtils.isEmpty(varRule)) return "";

            // url:http...$sub:截取規則
            if (varRule.startsWith("url:")) {
                String urlPart = varRule.substring(4);
                String subRule = "";
                int subIdx = urlPart.indexOf("$sub:");
                if (subIdx >= 0) {
                    subRule = urlPart.substring(subIdx + 5);
                    urlPart = urlPart.substring(0, subIdx);
                }
                urlPart = replaceStepVars(urlPart);
                OkResult res = KaiGeNet.smartRequest(this.siteUrl, "get", urlPart, null, getHeaders(null));
                String html = res.getBody();
                if (!TextUtils.isEmpty(html)) lastHtml = html;
                if (TextUtils.isEmpty(subRule)) return html != null ? html : "";
                return KaiGeEngine.doExtract(html, subRule, this.siteUrl, globalVarCache, lastHtml, rule).value;
            }

            // 含截取規則 → 從 lastHtml 截取
            if (varRule.contains("&&") || varRule.startsWith("j:") || varRule.startsWith("p:") || varRule.startsWith("xpath:")) {
                String src = TextUtils.isEmpty(lastHtml) ? "" : lastHtml;
                return KaiGeEngine.doExtract(src, varRule, this.siteUrl, globalVarCache, lastHtml, rule).value;
            }

            // [工具:xxx] 工具鏈
            if (varRule.startsWith("[工具:") && varRule.endsWith("]")) {
                String toolChain = varRule.substring(4, varRule.length() - 1);
                return KaiGeTool.process("", toolChain, this.siteUrl, globalVarCache, lastHtml, rule);
            }

            // 固定值直接返回（替換變量後）
            return replaceStepVars(varRule);
        } catch (Exception e) {
            logError("⚠️ [fetchVarValue] 異常: " + e.getMessage());
            return "";
        }
    }

    /**
     * 判斷一個 JSON 字段名是否是變量字段
     * 變量字段：名字裡含 - 且不是已知的功能字段名
     */
    private static final java.util.Set<String> KNOWN_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "site_url", "host", "site_name", "index_url", "index_rule", "ua", "headers",
        "debug", "cdndefend", "danmaku", "pic_jump", "pic_prefix", "pic_suffix",
        "pic_extract", "pic_host", "cate_url", "cate_method", "cate_body", "cate_page_1",
        "cate_item", "cate_id", "cate_name", "cate_pic", "cate_remarks", "cate_list_path",
        "search_url", "search_method", "search_body", "sc_item", "sc_id", "sc_name",
        "sc_pic", "sc_remarks", "detail_url", "detail_method", "detail_body",
        "dt_name", "dt_pic", "dt_remarks", "dt_actor", "dt_director", "dt_content", "dt_list", "dt_from",
        "classes", "filters", "play"
    ));

    private boolean isVarField(String name) {
        if (KNOWN_FIELDS.contains(name)) return false;
        return name.contains("-c") || name.contains("-b") || name.contains("-t")
            || name.contains("-d") || name.contains("-p") || name.contains("-u") || name.contains("-h");
    }

    private String getVarBaseName(String fieldName) {
        // 去掉後綴（-c / -b / -t / -d / -p / -u / -h），也去掉數字有效期如 -3600-c
        return fieldName.replaceAll("-\\d+-[cbdputh]$", "")
                        .replaceAll("-[cbdputh]$", "");
    }

    // ==================== 解析輪詢（playerContent 解析器數組） ====================

    /**
     * 執行解析輪詢：play.parsers 是解析方案數組
     * 每個方案是獨立的 steps，第一個成功的結果返回
     * 成功判斷：final_url 包含 .m3u8/.mp4（A），或配置了 success_contains（B）
     */
    private String runParsers(String originalUrl, JSONObject play) {
        JSONArray parsers = play.optJSONArray("parsers");
        if (parsers == null || parsers.length() == 0) return null;

        String successContains = play.optString("success_contains", "");

        for (int pi = 0; pi < parsers.length(); pi++) {
            try {
                JSONObject parser = parsers.getJSONObject(pi);
                String parserName = parser.optString("name", "Parser" + (pi + 1));
                JSONArray steps = parser.optJSONArray("steps");
                if (steps == null || steps.length() == 0) continue;

                logDebug("<b style='color:#9b59b6;'>[解析輪詢] 嘗試 [" + parserName + "]</b>");

                // 重置 varPool 的 final_url 到原始 URL
                varPool.put("final_url", originalUrl);
                varPool.put("play_id",   originalUrl);

                boolean stepOk = false;
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.getJSONObject(i);
                    String stepUrl  = replaceStepVars(step.optString("url", varPool.containsKey("final_url") ? varPool.get("final_url") : ""));
                    String method   = step.optString("method", "get");

                    OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl,
                        replaceStepVars(step.optString("body", "")), getHeaders(step.optJSONObject("headers")));
                    String html = res.getBody();
                    if (!TextUtils.isEmpty(html)) lastHtml = html;

                    JSONObject vars = step.optJSONObject("vars");
                    if (vars != null) {
                        for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                            String k     = it.next();
                            String vRule = vars.optString(k);
                            String[] alternatives = vRule.split("\\|\\|");
                            for (String alt : alternatives) {
                                String val = KaiGeEngine.doExtract(html, alt.trim(), this.siteUrl, globalVarCache, lastHtml, rule).value;
                                if (!TextUtils.isEmpty(val)) {
                                    val = val.replace("\\/", "/").replace("\\", "").trim();
                                    varPool.put(k, val);
                                    if (k.contains("url") || k.matches("p[1-4]")) {
                                        varPool.put("final_url", val);
                                        stepOk = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                String finalUrl = (varPool.containsKey("final_url") ? varPool.get("final_url") : "").replace("\\/", "/");
                boolean success = isStreamUrl(finalUrl) ||
                    (!TextUtils.isEmpty(successContains) && finalUrl.contains(successContains)) ||
                    (TextUtils.isEmpty(successContains) && stepOk && !TextUtils.isEmpty(finalUrl));

                if (success) {
                    logDebug("<b style='color:#2ecc71;'>✅ [解析輪詢] [" + parserName + "] 成功: " + finalUrl + "</b>");
                    JSONObject resJson = new JSONObject();
                    resJson.put("parse",  0);
                    resJson.put("url",    finalUrl);
                    resJson.put("header", getPlayHeaders(play));
                    injectDanmaku(resJson, play);
                    return resJson.toString();
                } else {
                    logDebug("<span style='color:#f1c40f;'>⚠️ [解析輪詢] [" + parserName + "] 失敗，切下一個</span>");
                }
            } catch (Exception e) {
                logError("⚠️ [解析輪詢] 異常: " + e.getMessage());
            }
        }
        return null; // 全部失敗
    }

    private boolean isStreamUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv") || lower.contains(".ts");
    }

    // ==================== 生命週期 ====================

    @Override
    public void destroy() {
        try {
            if (picExecutor != null && !picExecutor.isShutdown()) {
                picExecutor.shutdownNow();
                logDebug("🧹 [线程池] 图片多线程池已释放");
            }
            picCache.clear();
            globalVarCache.clear();
        } catch (Exception ignored) {}
    }
}
