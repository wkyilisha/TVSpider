package com.github.catvod.spider;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.text.TextUtils;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaiGeSmart {

    // 🚀 1. 列表智慧化解析
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            vod.put("vod_name", findTitle(el));
            vod.put("vod_pic",  findPic(el));
            vod.put("vod_id",   findUrl(el));
            // 這裡的 remarks 抓取邏輯
            String remarks = el.select(".remarks, .state, .pic-text, .tag, .text-right").text().trim();
            vod.put("vod_remarks", remarks);
        } catch (Exception ignored) {}
        return vod;
    }

    // 🚀 2. 詳情智慧化解析
    public static JSONObject parseDetail(String html) {
        Document doc = Jsoup.parse(html);
        JSONObject vod = new JSONObject();

        // 標題
        Element titleNode = doc.selectFirst("h1, .title, .myui-content__detail h1");
        vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知標題");

        // 圖片
        Element imgNode = doc.selectFirst(".myui-content__thumb img, .picture img, img.lazyload, .vod-pic img");
        String pic = "";
        if (imgNode != null) {
            if (imgNode.hasAttr("data-original")) pic = imgNode.attr("data-original");
            else if (imgNode.hasAttr("data-src")) pic = imgNode.attr("data-src");
            else pic = imgNode.attr("src");
        }
        vod.put("vod_pic", pic);

        // 簡介 (長度優先)
        Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content");
        String bestContent = "";
        for (Element c : contents) {
            String t = c.text().trim();
            if (t.length() > bestContent.length()) bestContent = t;
        }
        vod.put("vod_content", bestContent);

        // 數據欄位 (主演、導演、地區、年份、更新)
        Elements dataNodes = doc.select(".data, p, li, .myui-content__detail p, .myui-content__detail li");
        for (Element node : dataNodes) {
            String text = node.text();
            if (text.contains("主演")) {
                vod.put("vod_actor", getTagsOrText(node, "主演"));
            } else if (text.contains("导演") || text.contains("導演")) {
                vod.put("vod_director", getTagsOrText(node, "导演"));
            } else if (text.contains("地区") || text.contains("地區")) {
                vod.put("vod_area", getTagsOrText(node, "地区"));
            } else if (text.contains("年份") || text.contains("年代") || text.contains("上映")) {
                vod.put("vod_year", getTagsOrText(node, "年份"));
            } else if (text.contains("更新") || text.contains("狀態") || text.contains("状态")) {
                String rem = text.replaceAll("更新[:：]", "").replaceAll("狀態[:：]", "").trim();
                vod.put("vod_remarks", rem);
            }
        }

        // 解析播放列表
        processPlaylist(doc, vod);

        return vod;
    }

    // 🚀 3. 播放列表智慧解析
    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line], .playlist-tab li");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list");

            if (blocks.isEmpty()) {
                String links = findAllLinks(doc);
                if (!links.isEmpty()) {
                    fromList.add("默認線路");
                    urlList.add(links);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String name = (i < tabs.size()) ? tabs.get(i).text().trim() : "";
                    if (TextUtils.isEmpty(name)) name = "線路 " + (i + 1);
                    String links = findAllLinks(blocks.get(i));
                    if (!links.isEmpty()) {
                        fromList.add(name);
                        urlList.add(links);
                    }
                }
            }
            // 修正線路分隔符為 $$$
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));
            vod.put("vod_play_url",  TextUtils.join("$$$", urlList));
        } catch (Exception ignored) {}
    }

    // 🚀 4. 輔助工具方法
    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            if (h.contains("/") && n.length() < 20 && !n.contains("下載")) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    private static String getTagsOrText(Element node, String key) {
        Elements links = node.select("a");
        if (!links.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element a : links) {
                String t = a.text().trim();
                if (t.isEmpty() || t.equals("更多")) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(t);
            }
            return sb.toString();
        }
        return node.text().replaceAll(key + "[:：]", "").trim();
    }

    public static String findPic(Element el) {
        String[] attrs = {"data-original", "data-src", "src", "data-main"};
        for (String a : attrs) {
            String val = el.attr(a).trim();
            if (!val.isEmpty() && !val.contains(".gif")) return val;
        }
        Element img = el.selectFirst("img");
        if (img != null) {
            for (String a : attrs) {
                String val = img.attr(a).trim();
                if (!val.isEmpty() && !val.contains(".gif")) return val;
            }
        }
        return "";
    }

    public static String findTitle(Element el) {
        String t = el.attr("title").trim();
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,.title,.name");
            t = (h != null) ? h.text().trim() : el.text().trim();
        }
        return t;
    }

    public static String findUrl(Element el) {
        Element a = el.is("a") ? el : el.selectFirst("a");
        return a != null ? a.attr("href") : "";
    }
}
