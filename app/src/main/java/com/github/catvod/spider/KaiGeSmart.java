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
     * 🚀 列表打包器：負責將 HTML 轉為 TVBox 要求的 JSON 列表
     * 你在 JSON 規則裡定位好的容器 HTML 片段傳入此處
     */
    public static String buildResult(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();
            
            // 如果已經是 JSON（API 返回），直接返回
            if (trimData.startsWith("{")) {
                return trimData; 
            }
            
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            Document doc = Jsoup.parse(trimData);
            
            // 抓取所有子節點（即你在 JSON 規則中 select 出來的每一個視頻塊）
            // 如果傳入的是整個頁面，它會嘗試在裡面找有連結的塊
            Elements items = doc.body().children();
            if (items.isEmpty()) {
                items = doc.select("a, div, li"); // 備選掃描
            }
            
            for (Element el : items) {
                JSONObject vod = parseList(el);
                // 必須有有效的 ID 才加入列表
                if (vod.has("vod_id") && !TextUtils.isEmpty(vod.optString("vod_id"))) {
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
     * 🚀 列表項解析：精準提取 標題、圖片、ID、備註
     */
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            String id = findUrl(el);
            // 過濾無效連結
            if (TextUtils.isEmpty(id) || id.contains("javascript") || id.length() < 2) return vod;

            vod.put("vod_id", id);
            vod.put("vod_name", findTitle(el));
            vod.put("vod_pic", findPic(el));
            vod.put("vod_remarks", findRemarks(el));
        } catch (Exception ignored) {}
        return vod;
    }

    /**
     * 🚀 詳情頁打包器：解析影片詳情、演員、簡介及播放列表
     */
    public static String buildDetail(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();
            if (trimData.startsWith("{")) return trimData;

            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            
            Document doc = Jsoup.parse(trimData);
            JSONObject vod = new JSONObject();
            
            // 提取標題
            Element titleNode = doc.selectFirst("h1, h2, .title, .name, .module-info-heading h1, .detail-title");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知影片");
            
            // 提取圖片
            vod.put("vod_pic", findPic(doc));

            // 提取內容簡介
            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content, .module-info-introduction-content, .detail-content");
            String bestContent = "";
            for (Element c : contents) {
                if (c.text().length() > bestContent.length()) bestContent = c.text().trim();
            }
            vod.put("vod_content", bestContent);

            // 提取演員、導演、地區、年份
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
            
            // 處理播放列表
            processPlaylist(doc, vod);
            
            list.put(vod);
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * 🚀 播放列表處理器：支持多線路識別
     */
    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            
            // 識別線路標題（Tab）
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line], .playlist-tab li, .myui-panel__head li, .module-tab-item, .anthology-tab a, .stui-pannel__head li");
            // 識別播放列表塊
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list, .myui-panel_bd .tab-content, .module-play-list, .anthology-list-box, .stui-content__playlist");

            if (blocks.isEmpty()) {
                // 如果沒有明顯的播放列表塊，嘗試全局嗅探所有播放連結
                String links = findAllLinks(doc);
                if (!links.isEmpty()) {
                    fromList.add("默認線路");
                    urlList.add(links);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String name = (i < tabs.size()) ? tabs.get(i).text().trim() : "線路 " + (i + 1);
                    // 過濾掉不像是線路名稱的標籤
                    if (name.length() > 10) name = "線路 " + (i + 1);
                    
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

    /**
     * 🚀 提取特定容器內的所有播放連結
     */
    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        Elements links = root.select("a");
        for (Element a : links) {
            String n = a.text().trim();
            String h = a.attr("href");
            // 排除明顯的無效連結
            if (!h.isEmpty() && !h.contains("javascript") && n.length() < 30 && !n.contains("下載")) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    /**
     * 🚀 圖片智慧嗅探：兼容 lazyload 和背景圖
     */
    public static String findPic(Element el) {
        // 1. 掃描 img 標籤的所有潛在屬性
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            String[] attrs = {"data-original", "data-src", "src", "data-main", "data-lazy-src", "data-srcset"};
            for (String a : attrs) {
                String val = img.attr(a).trim();
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/") || val.startsWith("//"))) {
                    if (val.startsWith("//")) val = "http:" + val;
                    return val;
                }
            }
        }
        // 2. 掃描背景圖 style
        Elements bgs = el.select("[style*='url']");
        if (bgs.isEmpty() && el.attr("style").contains("url(")) bgs.add(el);
        for (Element bg : bgs) {
            String style = bg.attr("style");
            if (style.contains("url(")) {
                try {
                    String val = style.substring(style.indexOf("url(") + 4, style.lastIndexOf(")")).replace("'", "").replace("\"", "");
                    if (val.startsWith("//")) val = "http:" + val;
                    return val;
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    /**
     * 🚀 標題智慧提取：多級回溯
     */
    public static String findTitle(Element el) {
        // 1. 優先取節點自身的 title
        String t = el.attr("title").trim();
        // 2. 找內部 a 標籤的 title
        if (t.isEmpty()) t = el.select("a").attr("title").trim();
        // 3. 找內部 img 的 alt
        if (t.isEmpty()) t = el.select("img").attr("alt").trim();
        // 4. 找常見標題標籤
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,h4,h5,p,font,.title,.name");
            t = (h != null) ? h.text().trim() : "";
        }
        // 5. 兜底取 a 的文本
        if (t.isEmpty()) {
            Element a = el.selectFirst("a");
            t = (a != null) ? a.text().trim() : "";
        }
        return t;
    }

    /**
     * 🚀 連結智慧提取
     */
    public static String findUrl(Element el) {
        if (el.tagName().equals("a")) return el.attr("href");
        // 優先找包含影片關鍵字的連結
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='show'], a[href*='play'], a[href*='v-'], a[href$='.html']");
        if (a == null) a = el.selectFirst("a");
        return a != null ? a.attr("href") : "";
    }

    /**
     * 🚀 備註提取：更新至XX集、HD、高清等
     */
    private static String findRemarks(Element el) {
        // 1. 根據類名精準找
        Element node = el.selectFirst(".pic-text, .remarks, .state, .pic-tag-bottom, .tag, .label, .badge, .status, .text-right");
        if (node != null) return node.text().trim();
        
        // 2. 根據關鍵字模糊掃描
        Elements tags = el.select("span, em, b, i, p");
        for (Element tag : tags) {
            String text = tag.text().trim();
            if (text.matches(".*(更新|至|[0-9]集|期|完|版|HD|BD|TS|蓝|藍|1080|720).*")) {
                if (text.contains("分") && text.length() < 4) continue;
                return text;
            }
        }
        return "";
    }

    /**
     * 🚀 輔助方法：獲取標籤內容或純文本
     */
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
