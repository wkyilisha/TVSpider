package com.github.catvod.spider;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.text.TextUtils;
import java.util.*;

public class KaiGeSmart {

    // 🚀 1. 列表與搜索智慧化解析 (通用於分類與搜索)
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            // 標題
            vod.put("vod_name", findTitle(el));
            
            // 網址 (findUrl 內置了自動補全邏輯)
            vod.put("vod_id", findUrl(el));
            
            // 圖片 (深度掃描： data-original -> data-src -> src -> background-image)
            vod.put("vod_pic", findPic(el));

            // 更新/備註 (全類名掃描)
            String remarks = el.select(".remarks, .state, .pic-text, .tag, .text-right, .pic-tag, .label, .badge, .publicer, .fe-right").text().trim();
            
            // 降級方案：如果沒標籤，找最後一個含有數字或「完」字關鍵字的 span
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
        } catch (Exception ignored) {
            // 確保單個列表項解析失敗不影響整體
        }
        return vod;
    }

    // 🚀 2. 詳情智慧化解析 (修復了 JSONObject 編譯錯誤)
    public static JSONObject parseDetail(String html) {
        Document doc = Jsoup.parse(html);
        JSONObject vod = new JSONObject();
        try {
            // 1. 標題
            Element titleNode = doc.selectFirst("h1, .title, .myui-content__detail h1");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知標題");

            // 2. 圖片
            vod.put("vod_pic", findPic(doc));

            // 3. 內容 (長度優先：自動篩選最完整的簡介)
            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content");
            String bestContent = "";
            for (Element c : contents) {
                String t = c.text().trim();
                if (t.length() > bestContent.length()) bestContent = t;
            }
            vod.put("vod_content", bestContent);

            // 4. 數據欄位掃描 (關鍵字智慧匹配)
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
                    String rem = text.replaceAll("更新[:：]", "").replaceAll("狀態[:：]", "").replaceAll("状态[:：]", "").trim();
                    vod.put("vod_remarks", rem);
                }
            }

            // 5. 解析播放列表
            processPlaylist(doc, vod);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return vod;
    }

    // 🚀 3. 播放列表智慧解析
    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, [data-line], .playlist-tab li, .myui-panel__head li");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list, .myui-panel_bd .tab-content");

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
        // 抓取區塊內所有 a 標籤
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            // 過濾無效連結與明顯不是集數的長文本
            if (!h.isEmpty() && n.length() < 25 && !n.contains("下載") && !n.contains("詳情")) {
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

    // 🚀 5. 強化版圖片提取器：應對懶加載與 CSS 背景圖
    public static String findPic(Element el) {
        String[] attrs = {"data-original", "data-src", "data-main", "src", "style"};
        
        // 1. 優先找 img 標籤
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            for (String a : attrs) {
                String val = img.attr(a).trim();
                // 處理背景圖 style="background-image:url(...)"
                if (a.equals("style") && val.contains("url(")) {
                    try {
                        val = val.substring(val.indexOf("url(") + 4, val.lastIndexOf(")")).replace("'", "").replace("\"", "");
                    } catch (Exception ignored) {}
                }
                // 排除佔位圖，只返回真實圖片地址
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/"))) {
                    return val;
                }
            }
        }

        // 2. 備選方案：找帶有背景圖或 lazyload 類名的容器 (div 或 a)
        Element thumb = el.selectFirst("[style*='url'], .lazyload, .videopic, .cover, .thumb");
        if (thumb != null) {
            String style = thumb.attr("style");
            if (style != null && style.contains("url(")) {
                try {
                    return style.substring(style.indexOf("url(") + 4, style.lastIndexOf(")")).replace("'", "").replace("\"", "");
                } catch (Exception ignored) {}
            }
            for (String a : attrs) {
                String val = thumb.attr(a).trim();
                if (!val.isEmpty() && !val.contains(".gif") && (val.startsWith("http") || val.startsWith("/"))) return val;
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

    // 🚀 6. 強化版 URL 提取器：優先找影視詳情連結
    public static String findUrl(Element el) {
        // 優先找包含影視特徵字樣的連結，排除掉廣告或無關 a 標籤
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='show'], a[href*='play'], a[href*='.html']");
        
        // 如果上面沒找到，就隨便找容器內第一個 a
        if (a == null) {
            a = el.is("a") ? el : el.selectFirst("a");
        }
        
        if (a != null) {
            return a.attr("href");
        }
        return "";
    }
}
