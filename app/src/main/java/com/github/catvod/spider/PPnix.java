package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;
import java.util.regex.*;

public class PPnix extends Spider {

    private static final String HOST      = "https://www.ppnix.com";
    private static final String UA        = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String PLAY_FROM = "PPnix";

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        return h;
    }

    private String get(String url, String referer) {
        try {
            return KaiGeNet.smartRequest(HOST, "get", url, null, baseHeaders(referer)).getBody();
        } catch (Exception e) {
            logger("🚨 [请求失败] " + url + " → " + e.getMessage());
            return "";
        }
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        // 主动启动 Proxy 服务器，不弹任何窗口
        Proxy.log("PPnix 插件加载成功");
    }

    // ──────────────────────────────────────────────
    // 首页分类
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("电影",   "movie"));
            classes.put(makeClass("电视剧", "tv"));

            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list",  new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject makeClass(String name, String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type_name", name);
        o.put("type_id",   id);
        return o;
    }

    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // 分类列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page      = Integer.parseInt(pg);
            int pageIndex = page - 1;
            String url    = HOST + "/cn/" + tid + "/---" + pageIndex + "-.html";

            logger("📂 [分类] " + tid + " 第" + page + "页 → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;

                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img = thumbA.selectFirst("img");
                String pic  = img != null ? (img.hasAttr("src") ? img.attr("src") : img.attr("data-src")) : "";

                Element yearSpan = li.selectFirst(".countrie .orange");
                String remarks   = yearSpan != null ? yearSpan.text().trim() : "";

                Element titleA = li.selectFirst("h2 a");
                String name    = titleA != null ? titleA.text().trim() : "";

                JSONObject vod = new JSONObject();
                vod.put("vod_id",      vodId);
                vod.put("vod_name",    name);
                vod.put("vod_pic",     pic);
                vod.put("vod_remarks", remarks);
                list.put(vod);
            }

            logger("✅ [分类] 获取到 " + list.length() + " 条");
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", page);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [分类异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ──────────────────────────────────────────────
    // 详情页
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id  = ids.get(0);
            String url = id.startsWith("http") ? id : HOST + id;

            logger("📄 [详情] → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);

            String name = "", year = "";
            Element titleElem = doc.selectFirst("h1.product-title");
            if (titleElem != null) {
                String fullText = titleElem.text().trim();
                Matcher m = Pattern.compile("(.+?)\\s*\\((\\d{4})\\)").matcher(fullText);
                if (m.find()) {
                    name = m.group(1).trim();
                    year = m.group(2);
                } else {
                    name = fullText;
                }
            }

            String pic = "";
            Element picElem = doc.selectFirst(".product-header img.thumb");
            if (picElem != null) {
                pic = picElem.attr("src");
                if (pic.startsWith("/")) pic = HOST + pic;
            }

            String director = extractLinks(doc, "导演");
            String actor    = extractLinks(doc, "主演");
            String area     = extractLinks(doc, "国家");
            String content  = "";
            Element descElem = doc.selectFirst(".product-excerpt:contains(简介) span");
            if (descElem != null) content = descElem.text().trim();

            String scriptText = "";
            for (Element script : doc.select("script")) {
                String s = script.html();
                if (s.contains("infoid") && s.contains("m3u8")) {
                    scriptText = s;
                    break;
                }
            }

            String   infoid   = "";
            String[] episodes = new String[0];

            if (!TextUtils.isEmpty(scriptText)) {
                Matcher mId = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(scriptText);
                if (mId.find()) infoid = mId.group(1);

                Matcher mArr = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)]").matcher(scriptText);
                if (mArr.find()) {
                    String arrContent = mArr.group(1);
                    List<String> epList = new ArrayList<>();
                    Matcher mEp = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrContent);
                    while (mEp.find()) epList.add(mEp.group(1));
                    episodes = epList.toArray(new String[0]);
                }
            }

            String playFrom = "";
            String playUrl  = "";
            if (!TextUtils.isEmpty(infoid) && episodes.length > 0) {
                String categoryType = id.contains("/movie/") ? "movie" : "tv";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < episodes.length; i++) {
                    if (i > 0) sb.append("#");
                    sb.append("第").append(episodes[i]).append("集")
                      .append("$")
                      .append("/info/m3u8/").append(infoid).append("/").append(episodes[i]).append(".m3u8?type=").append(categoryType);
                }
                playFrom = PLAY_FROM;
                playUrl  = sb.toString();
                logger("✅ [详情] infoid=" + infoid + " 共" + episodes.length + "集");
            } else {
                logger("⚠️ [详情] 未找到播放源");
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id",        id);
            vod.put("vod_name",      name);
            vod.put("vod_pic",       pic);
            vod.put("vod_year",      year);
            vod.put("vod_area",      area);
            vod.put("vod_director",  director);
            vod.put("vod_actor",     actor);
            vod.put("vod_content",   content);
            vod.put("vod_remarks",   TextUtils.isEmpty(year) ? "" : year + "年");
            vod.put("vod_play_from", playFrom);
            vod.put("vod_play_url",  playUrl);

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [详情异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    private String extractLinks(Document doc, String label) {
        try {
            Element span = doc.selectFirst(".product-excerpt:contains(" + label + ") span");
            if (span == null) return "";
            List<String> texts = new ArrayList<>();
            for (Element a : span.select("a")) texts.add(a.text().trim());
            return TextUtils.join(", ", texts);
        } catch (Exception e) {
            return "";
        }
    }

    // ──────────────────────────────────────────────
    // 播放（走 Proxy 本地代理）
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String originalUrl = id.startsWith("http") ? id : HOST + id;

            // 交给 Proxy 的 ?do=m3u8&url= 进行域名随机替换后返回给播放器
            String proxyUrl = Proxy.getUrl() + "?do=m3u8&url=" + java.net.URLEncoder.encode(originalUrl, "UTF-8");

            logger("▶️ [播放] 原始URL: " + originalUrl);
            logger("🔀 [播放] 代理URL: " + proxyUrl);

            JSONObject headers = new JSONObject();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");
            headers.put("Origin", HOST);

            JSONObject result = new JSONObject();
            result.put("parse",  0);
            result.put("url",    proxyUrl);
            result.put("header", headers.toString());
            return result.toString();

        } catch (Exception e) {
            logger("🚨 [播放异常] " + e.getMessage());
            return "{}";
        }
    }

    // ──────────────────────────────────────────────
    // 搜索
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url  = HOST + "/search.php?searchword=" + java.net.URLEncoder.encode(key, "UTF-8");
            logger("🔍 [搜索] → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;

                String vodId = thumbA.attr("href");
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img = thumbA.selectFirst("img");
                String pic  = img != null ? (img.hasAttr("src") ? img.attr("src") : img.attr("data-src")) : "";

                Element titleA = li.selectFirst("h2 a");
                String name    = titleA != null ? titleA.text().trim() : "";

                Element yearSpan = li.selectFirst(".countrie .orange");
                String remarks   = yearSpan != null ? yearSpan.text().trim() : "";

                JSONObject vod = new JSONObject();
                vod.put("vod_id",      vodId);
                vod.put("vod_name",    name);
                vod.put("vod_pic",     pic);
                vod.put("vod_remarks", remarks);
                list.put(vod);
            }

            logger("✅ [搜索] 共 " + list.length() + " 条结果");
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [搜索异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }
}
