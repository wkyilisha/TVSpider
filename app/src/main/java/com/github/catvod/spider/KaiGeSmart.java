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
            
            // 識別常見列表容器，增加了對主流模板類名的覆蓋
            Elements items = doc.select(".myui-vodlist__item, .vodlist_item, .fed-list-item, .pack-ykpack, .list-item, .v-item, .module-item, li:has(img)");
            
            for (Element el : items) {
                JSONObject vod = parseList(el);
                if (vod.length() > 0) list.put(vod);
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
            String name = findTitle(el);
            String id = findUrl(el);
            if (TextUtils.isEmpty(id)) return vod;

            vod.put("vod_name", name);
            vod.put("vod_id", id);
            
            // 🚀 修復：改進後的圖片抓取
            vod.put("vod_pic", findPic(el));

            // 🚀 修復：加強版更新狀態（vod_remarks）抓取
            String remarks = "";
            // 1. 高頻類名掃描
            Element remarkNode = el.selectFirst(".pic-text, .remarks, .state, .pic-tag-bottom, .tag, .label, .badge, .pic-tag, .text-right");
            if (remarkNode != null) {
                remarks = remarkNode.text().trim();
            }

            // 2. 語義模糊掃描
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
            
            // 3. 降級方案
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
            Element titleNode = doc.selectFirst("h1, .title, .myui-content__detail h1, .module-info-heading h1");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知標題");
            vod.put("vod_pic", findPic(doc));

            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content, .module-info-introduction-content");
            String bestContent = "";
            for (Element c : contents) {
                if (c.text().length() > bestContent.length()) bestContent = c.text().trim();
            }
            vod.put("vod_content", bestContent);

            Elements dataNodes = doc.select(".data, p, li, .myui-content__detail p, .module-info-item");
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
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line], .playlist-tab li, .myui-panel__head li, .module-tab-item");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list, .myui-panel_bd .tab-content, .module-play-list");

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
            if (!h.isEmpty() && n.length() < 25 && !n.contains("下載")) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    /**
     * 🚀 核心優化：更強大的圖片嗅探
     */
    public static String findPic(Element el) {
        // 1. 優先處理 img 標籤的各種屬性
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            String[] attrs = {"data-original", "data-src", "src", "data-main"};
            for (String a : attrs) {
                String val = img.attr(a).trim();
                // 排除 gif 加載圖，兼容 // 和 / 開頭的地址
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/") || val.startsWith("//"))) {
                    return val;
                }
            }
        }
        
        // 2. 備選方案：處理帶有 style 背景圖的標籤 (常用於懶加載容器)
        Elements bgs = el.select("[style*='url']");
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
        String t = el.attr("title").trim();
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,.title,.name,.module-item-title");
            t = (h != null) ? h.text().trim() : "";
        }
        // 如果還是沒標題，取第一個 a 標籤的 text
        if (t.isEmpty()) {
            Element a = el.selectFirst("a");
            t = (a != null) ? a.text().trim() : "";
        }
        return t;
    }

    public static String findUrl(Element el) {
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='show'], a[href*='play'], a[href*='.html']");
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
