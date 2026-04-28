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

    // 🚀 列表智慧化
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            vod.put("vod_name", findTitle(el));
            vod.put("vod_pic",  findPic(el));
            vod.put("vod_id",   findUrl(el));
            String remarks = el.select(".remarks, .state, .pic-text, .tag, .text-right").text().trim();
            vod.put("vod_remarks", remarks);
        } catch (Exception e) {}
        return vod;
    }

    // 🚀 詳情頁智慧化
    public static JSONObject parseDetail(String html) {
        JSONObject vod = new JSONObject();
        try {
            // 🛡️ 修正點：使用 Jsoup.parse
            Document doc = Jsoup.parse(html);
            vod.put("vod_name", findTitle(doc));
            vod.put("vod_pic",  findPic(doc));

            String info = doc.select(".detail-info, .info, .data, .vod-detail").text();
            vod.put("vod_director", findAttr(info, "導[演演]"));
            vod.put("vod_actor",    findAttr(info, "[演演][員員]|主演"));
            vod.put("vod_area",     findAttr(info, "[地地][區區]"));
            vod.put("vod_year",     findAttr(info, "[年年][份份]|上映"));
            vod.put("vod_content",  findContent(doc));

            processPlaylist(doc, vod);
        } catch (Exception e) {}
        return vod;
    }

    private static void processPlaylist(Document doc, JSONObject vod) {
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line]");
        Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist");

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
        vod.put("vod_play_from", TextUtils.join("$$$", fromList));
        vod.put("vod_play_url",  TextUtils.join("###", urlList));
    }

    public static String findPic(Element el) {
        String[] attrs = {"data-original", "data-src", "src", "data-main"};
        for (String a : attrs) {
            String val = el.attr(a).trim();
            if (!val.isEmpty() && !val.contains(".gif")) return val;
        }
        Element img = el.selectFirst("img");
        return img != null ? findPic(img) : "";
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

    private static String findAttr(String text, String reg) {
        Matcher m = Pattern.compile(reg + "[:：]\\s*([^\\s/|]+)").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            if (h.contains("/") && n.length() < 15) sb.append(n).append("$").append(h).append("#");
        }
        return sb.toString().endsWith("#") ? sb.substring(0, sb.length()-1) : sb.toString();
    }

    private static String findContent(Document doc) {
        Element c = doc.selectFirst(".content, .detail-content, .desc, #plot");
        return c != null ? c.text().trim() : "";
    }
}
