package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.text.TextUtils;
import java.util.*;

public class KaiGeSmart {

    /**
     * 🚀 通用打包器：自動識別 JSON API 或 HTML 網頁
     */
    public static String buildResult(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();
            
            if (trimData.startsWith("{")) {
                return trimData; 
            }
            
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            Document doc = Jsoup.parse(trimData);
            
            // 💡 升級：1. 先按常見類名精準找
            Elements items = doc.select(".myui-vodlist__item, .vodlist_item, .fed-list-item, .pack-ykpack, .list-item, .v-item, .module-item, .stui-vodlist__item, li:has(img)");
            
            // 💡 升級：2. 如果抓不到，啟動「暴力掃描」：尋找所有包含圖片的 A 標籤
            if (items.isEmpty()) {
                items = doc.select("a:has(img)");
            }
            
            for (Element el : items) {
                // 根據 el 的類型（是容器還是 A 標籤本身）智慧解析
                JSONObject vod = parseList(el);
                // 確保 ID 和標題不為空才加入
                if (vod.has("vod_id") && !TextUtils.isEmpty(vod.optString("vod_name"))) {
                    list.put(vod);
                }
            }
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * 🚀 詳情打包器
     */
    public static String buildDetail(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();

            if (trimData.startsWith("{")) {
                return trimData;
            }

            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(parseDetail(trimData));
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            String id = findUrl(el);
            if (TextUtils.isEmpty(id) || id.contains("javascript")) return vod;

            String name = findTitle(el);
            String pic = findPic(el);

            // 如果在當前節點找不到圖或名，去它的父節點範圍擴大搜索（適配 a:has(img) 模式）
            if (TextUtils.isEmpty(pic) || TextUtils.isEmpty(name)) {
                Element p = el.parent();
                if (p != null) {
                    if (TextUtils.isEmpty(name)) name = findTitle(p);
                    if (TextUtils.isEmpty(pic)) pic = findPic(p);
                }
            }

            if (TextUtils.isEmpty(name)) return vod;

            vod.put("vod_name", name);
            vod.put("vod_id", id);
            vod.put("vod_pic", pic);

            // 🚀 核心修復：更穩定的更新狀態抓取
            String remarks = "";
            // 優先找特定標籤
            Element remarkNode = el.selectFirst(".pic-text, .remarks, .state, .pic-tag-bottom, .tag, .label, .badge, .pic-tag, .text-right, .status");
            if (remarkNode != null) {
                remarks = remarkNode.text().trim();
            }

            // 語義掃描補底
            if (TextUtils.isEmpty(remarks) || (remarks.contains("分") && remarks.length() < 5)) {
                Elements tags = el.select("span, em, b, i, p");
                for (Element tag : tags) {
                    String text = tag.text().trim();
                    if (text.matches(".*(更新|至|[0-9]集|期|完|版|HD|BD|TS|字|蓝|藍).*")) {
                        if (text.contains("分") && text.length() < 4) continue;
                        remarks = text;
                        break; 
                    }
                }
            }
            
            // 降級找數字
            if (TextUtils.isEmpty(remarks)) {
                Elements spans = el.select("span");
                for (int i = spans.size() - 1; i >= 0; i--) {
                    String sText = spans.get(i).text().trim();
                    if (sText.matches(".*[0-9完].*")) {
                        remarks = sText;
                        break;
                    }
                }
            }
            vod.put("vod_remarks", remarks);
        } catch (Exception ignored) {}
        return vod;
    }

    public static JSONObject parseDetail(String html) {
        Document doc = Jsoup.parse(html);
        JSONObject vod = new JSONObject();
        try {
            Element titleNode = doc.selectFirst("h1, .title, .myui-content__detail h1, .module-info-heading h1, .detail-title");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知標題");
            vod.put("vod_pic", findPic(doc));

            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content, .module-info-introduction-content, .detail-content");
            String bestContent = "";
            for (Element c : contents) {
                if (c.text().length() > bestContent.length()) bestContent = c.text().trim();
            }
            vod.put("vod_content", bestContent);

            Elements dataNodes = doc.select(".data, p, li, .myui-content__detail p, .module-info-item, .detail-info-item");
            for (Element node : dataNodes) {
                String text = node.text();
                if (text.contains("主演")) vod.put("vod_actor", getTagsOrText(node, "主演"));
                else if (text.contains("导演") || text.contains("導演")) vod.put("vod_director", getTagsOrText(node, "导演"));
                else if (text.contains("地区") || text.contains("地區")) vod.put("vod_area", getTagsOrText(node, "地区"));
                else if (text.contains("年份") || text.contains("年代")) vod.put("vod_year", getTagsOrText(node, "年份"));
                else if (text.matches(".*(更新|狀態|状态).*")) {
                    vod.put("vod_remarks", text.replaceAll(".*[:：]", "").trim());
                }
            }
            processPlaylist(doc, vod);
        } catch (Exception ignored) {}
        return vod;
    }

    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line], .playlist-tab li, .myui-panel__head li, .module-tab-item, .anthology-tab a");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list, .myui-panel_bd .tab-content, .module-play-list, .anthology-list-box");

            if (blocks.isEmpty()) {
                String links = findAllLinks(doc);
                if (!links.isEmpty()) {
                    fromList.add("默認線路");
                    urlList.add(links);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String name = (i < tabs.size()) ? tabs.get(i).text().trim() : "線路 " + (i + 1);
                    String links = findAllLinks(blocks.get(i));
                    if (!links.isEmpty()) {
                        fromList.add(name);
                        urlList.add(links);
                    }
                }
            }
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));
            vod.put("vod_play_url",  TextUtils.join("$$$", urlList));
        } catch (Exception ignored) {}
    }

    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            if (!h.isEmpty() && n.length() < 25 && !n.contains("下載") && !h.contains("javascript")) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    public static String findPic(Element el) {
        // 1. 處理 img 標籤
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            String[] attrs = {"data-original", "data-src", "src", "data-main", "data-lazy-src"};
            for (String a : attrs) {
                String val = img.attr(a).trim();
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/") || val.startsWith("//"))) {
                    return val;
                }
            }
        }
        // 2. 處理背景圖
        Elements bgs = el.select("[style*='url']");
        if (bgs.isEmpty() && el.attr("style").contains("url(")) bgs.add(el); 
        for (Element bg : bgs) {
            String style = bg.attr("style");
            if (style.contains("url(")) {
                try {
                    String val = style.substring(style.indexOf("url(") + 4, style.lastIndexOf(")")).replace("'", "").replace("\"", "");
                    if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/") || val.startsWith("//"))) {
                        return val;
                    }
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    public static String findTitle(Element el) {
        // 1. 優先從 a 的 title 或 img 的 alt 抓
        String t = el.attr("title").trim();
        if (t.isEmpty()) t = el.select("a").attr("title").trim();
        if (t.isEmpty()) t = el.select("img").attr("alt").trim();
        
        // 2. 找特定的標題標籤
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,h4,.title,.name,.module-item-title");
            t = (h != null) ? h.text().trim() : "";
        }
        
        // 3. 兜底找第一個 a 的文本
        if (t.isEmpty()) {
            Element a = el.selectFirst("a");
            t = (a != null) ? a.text().trim() : "";
        }
        return t;
    }

    public static String findUrl(Element el) {
        // 增加識別常見連結特徵
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='show'], a[href*='play'], a[href*='v-'], a[href$='.html']");
        if (a == null) a = el.is("a") ? el : el.selectFirst("a");
        return a != null ? a.attr("href") : "";
    }

    private static String getTagsOrText(Element node, String key) {
        Elements links = node.select("a");
        if (!links.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element a : links) {
                String t = a.text().trim();
                if (!t.isEmpty() && !t.equals("更多")) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return node.text().replaceAll(key + "[:：]", "").trim();
    }
}
