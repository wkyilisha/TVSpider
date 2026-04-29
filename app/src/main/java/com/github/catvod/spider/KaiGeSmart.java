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
     * 🚀 列表打包器
     * 邏輯：如果你在 JSON 裡定位了容器，這裡接收到的就是容器片段；
     * 如果沒定位，這裡接收的是全頁源碼，則啟動自動保底識別。
     */
    public static String buildResult(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();
            if (trimData.startsWith("{")) return trimData; 

            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            Document doc = Jsoup.parse(trimData);
            
            // 1. 獲取初始節點
            Elements items = doc.body().children();

            // 2. 💡 智能保底判斷：
            // 如果子節點太少（說明傳入的是整頁源碼而非預選容器），或者子節點裡沒連結
            // 則啟動「自動定位」模式，尋找常見的視頻容器類名或 a:has(img)
            if (items.size() < 3) { 
                items = doc.select(".myui-vodlist__item, .vodlist_item, .fed-list-item, .pack-ykpack, .list-item, .v-item, .module-item, .stui-vodlist__item, li:has(img), a:has(img)");
            }

            for (Element el : items) {
                JSONObject vod = parseList(el);
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
     * 🚀 列表項屬性提取
     */
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            String id = findUrl(el);
            if (TextUtils.isEmpty(id) || id.contains("javascript")) return vod;

            vod.put("vod_id", id);
            vod.put("vod_name", findTitle(el));
            vod.put("vod_pic", findPic(el));
            vod.put("vod_remarks", findRemarks(el));
        } catch (Exception ignored) {}
        return vod;
    }

    /**
     * 🚀 詳情頁解析
     */
    public static String buildDetail(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            Document doc = Jsoup.parse(data);
            JSONObject vod = new JSONObject();
            
            Element titleNode = doc.selectFirst("h1, h2, .title, .name, .module-info-heading h1");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知影片");
            vod.put("vod_pic", findPic(doc));

            // 簡介處理
            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content, .module-info-introduction-content");
            String bestContent = "";
            for (Element c : contents) {
                if (c.text().length() > bestContent.length()) bestContent = c.text().trim();
            }
            vod.put("vod_content", bestContent);

            // 數據列處理
            Elements dataNodes = doc.select(".data, p, li, .module-info-item");
            for (Element node : dataNodes) {
                String text = node.text();
                if (text.contains("主演")) vod.put("vod_actor", getTagsOrText(node, "主演"));
                else if (text.contains("导演") || text.contains("導演")) vod.put("vod_director", getTagsOrText(node, "导演"));
                else if (text.contains("地区") || text.contains("地區")) vod.put("vod_area", getTagsOrText(node, "地区"));
                else if (text.contains("年份") || text.contains("年代")) vod.put("vod_year", getTagsOrText(node, "年份"));
            }
            
            processPlaylist(doc, vod);
            
            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * 🚀 播放列表識別
     */
    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> from = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, .playlist-tab li, .module-tab-item");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .module-play-list");

            if (blocks.isEmpty()) {
                String links = findAllLinks(doc);
                if (!links.isEmpty()) {
                    from.add("默認線路");
                    urls.add(links);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String name = (i < tabs.size()) ? tabs.get(i).text().trim() : "線路 " + (i + 1);
                    String links = findAllLinks(blocks.get(i));
                    if (!links.isEmpty()) {
                        from.add(name);
                        urls.add(links);
                    }
                }
            }
            vod.put("vod_play_from", TextUtils.join("$$$", from));
            vod.put("vod_play_url",  TextUtils.join("$$$", urls));
        } catch (Exception ignored) {}
    }

    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            if (!h.isEmpty() && !h.contains("javascript") && n.length() < 30) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    /**
     * 🚀 圖片提取 (支持屬性 & 背景圖)
     */
    public static String findPic(Element el) {
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            String[] attrs = {"data-original", "data-src", "src", "data-main", "data-lazy-src"};
            for (String a : attrs) {
                String val = img.attr(a).trim();
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/") || val.startsWith("//"))) {
                    if (val.startsWith("//")) val = "http:" + val;
                    return val;
                }
            }
        }
        String style = el.attr("style");
        if (style.contains("url(")) {
            try {
                String val = style.substring(style.indexOf("url(") + 4, style.lastIndexOf(")")).replace("'", "").replace("\"", "");
                if (val.startsWith("//")) val = "http:" + val;
                return val;
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 🚀 標題提取 (智慧回溯)
     */
    public static String findTitle(Element el) {
        String t = el.attr("title").trim();
        if (t.isEmpty()) t = el.select("a").attr("title").trim();
        if (t.isEmpty()) t = el.select("img").attr("alt").trim();
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,h4,h5,.title,.name");
            t = (h != null) ? h.text().trim() : "";
        }
        if (t.isEmpty()) {
            Element a = el.selectFirst("a");
            t = (a != null) ? a.text().trim() : "";
        }
        return t;
    }

    /**
     * 🚀 連結提取
     */
    public static String findUrl(Element el) {
        if (el.tagName().equals("a")) return el.attr("href");
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='play'], a[href*='v-'], a[href$='.html']");
        return a != null ? a.attr("href") : "";
    }

    /**
     * 🚀 備註提取
     */
    private static String findRemarks(Element el) {
        Element node = el.selectFirst(".pic-text, .remarks, .state, .tag, .label, .badge, .status");
        if (node != null) return node.text().trim();
        Elements tags = el.select("span, em, b, i");
        for (Element tag : tags) {
            String text = tag.text().trim();
            if (text.matches(".*(更新|至|[0-9]集|期|完|版|HD|BD|蓝|藍).*")) return text;
        }
        return "";
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
