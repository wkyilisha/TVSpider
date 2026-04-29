package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.text.TextUtils;
import java.net.URLDecoder;
import java.util.*;

public class KaiGeFilter {

    /**
     * 🚀 凱哥智慧嗅探：自動抓取並生成 CatVod 格式的篩選列表
     */
    public static JSONObject getSmartFilters(String html) {
        JSONObject filterData = new JSONObject();
        try {
            Document doc = Jsoup.parse(html);
            // 1. 識別常見的篩選容器（適配 MyUI, AppleCMS, 122, 飛飛等）
            Elements rows = doc.select(".myui-screen__list, .scroll-box, .filter-list, .fed-part-case, .qy-search-filter, .filter-item, .sort-list");

            for (Element row : rows) {
                // 抓取篩選標題（例如：按地區、按年份）
                Element headNode = row.selectFirst(".text-muted, dt, .fed-text-muted, .filter-title, em, .label");
                if (headNode == null) continue;
                
                String head = headNode.text().replace("按", "").replace("：", "").replace(":", "").trim();
                String key = mapKey(head); // 轉換為 class, area, year 等標准 Key
                
                JSONArray options = new JSONArray();
                Elements links = row.select("a");

                for (Element link : links) {
                    String name = link.text().trim();
                    String href = link.attr("href");
                    
                    if (name.isEmpty() || name.equals("更多") || name.equals("詳情")) continue;

                    JSONObject option = new JSONObject();
                    option.put("n", name); // 顯示的繁簡體名字
                    
                    // 智慧提取 ID：全部為空，其他的從 URL 摳
                    String value = (name.contains("全部") || name.equals("全部")) ? "" : extractId(href, key);
                    option.put("v", value);
                    options.put(option);
                }

                if (options.length() > 0) {
                    JSONObject filterObj = new JSONObject();
                    filterObj.put("key", key);
                    filterObj.put("name", head);
                    filterObj.put("value", options);
                    filterData.put(key, filterObj); 
                }
            }
        } catch (Exception ignored) {}
        return filterData;
    }

    /**
     * 智慧 Key 映射：確保繁簡體都能識別並對應到 TVBox 的標准 Key
     */
    private static String mapKey(String head) {
        if (head.contains("類") || head.contains("类") || head.contains("型")) return "class";
        if (head.contains("地") || head.contains("區") || head.contains("区")) return "area";
        if (head.contains("年") || head.contains("代")) return "year";
        if (head.contains("語") || head.contains("语") || head.contains("言")) return "lang";
        if (head.contains("排") || head.contains("序")) return "by";
        return head;
    }

    /**
     * 智慧提取 ID：從 href 中切出網站需要的參數
     */
    private static String extractId(String url, String key) {
        try {
            if (TextUtils.isEmpty(url) || url.startsWith("javascript")) return "";
            
            // 解碼 URL（防止中文地區變成 %E7%BE%8E%E5%9B%BD）
            String decodedUrl = URLDecoder.decode(url, "UTF-8");
            String filename = decodedUrl.substring(decodedUrl.lastIndexOf("/") + 1).split("\\.")[0];
            
            if (filename.contains("-")) {
                String[] parts = filename.split("-");
                // 根據位置智慧猜測參數位（大部分 CMS 的規則）
                if (key.equals("class") && parts.length > 0) return parts[0];
                if (key.equals("area") && parts.length > 1) return parts[1];
                if (key.equals("lang") && parts.length > 2) return parts[2];
                if (key.equals("year") && parts.length > 3) return parts[3];
                if (key.equals("by") && parts.length > 4) return parts[4];
            }
            return filename;
        } catch (Exception e) {
            return "";
        }
    }
}
