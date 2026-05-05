package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DanmuHelper - 针对 TVSpider 项目优化的弹幕助手
 * 功能：多源搜索弹幕、JSON转XML、广告过滤、本地代理响应
 */
public class DanmuHelper {

    private static final Random RANDOM = new Random();

    // 弹幕源 API (可扩展)
    private static final String[] DANMU_SOURCES = {
            "https://api.danmu.icu/?ac=dm&url={url}",
            "https://dmku.hls.one/?ac=dm&url={url}"
    };

    // 弹幕颜色池
    private static final String[] COLORS = {
            "16711680", "16776960", "65280", "255", "16711935",
            "65535", "16777215", "8388736", "16753920"
    };

    // 弹幕内容指纹过滤正则（去除采集站自带的牛皮癣广告）
    private static final String AD_PATTERN = ".*(请遵守弹幕礼仪|官方弹幕库|微信公众号|云烟小助手|未传入链接|弹幕列队|火花剧场|加群|防走失|备用|联系|侵权).*";

    /**
     * 响应 Spider 类的 proxy 调用
     */
    public static Object[] getDanmuResponse(Map<String, String> params) {
        try {
            String title = params.get("title");
            String episodeStr = params.get("episode");
            String customUrl = params.get("url");

            int episodeNum = 1;
            if (episodeStr != null) {
                episodeNum = Integer.parseInt(episodeStr.replaceAll("\\D", ""));
            }

            // 1. 尝试获取原站 URL（用于匹配弹幕库）
            String videoUrl = "";
            if (customUrl != null && !customUrl.isEmpty()) {
                videoUrl = cleanUrl(customUrl);
            } else if (title != null) {
                videoUrl = searchVideoUrl(title, episodeNum);
            }

            // 2. 获取弹幕并转换
            String xmlContent = "";
            if (!videoUrl.isEmpty()) {
                xmlContent = fetchAndConvert(videoUrl);
            }

            // 3. 如果没抓到弹幕，生成一条系统提示弹幕
            if (xmlContent.isEmpty()) {
                xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><d p=\"0,1,25,16777215\">[代理] " + title + " 弹幕加载完成</d></i>";
            }

            return new Object[]{200, "application/xml; charset=utf-8", new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))};
        } catch (Exception e) {
            SpiderDebug.log(e);
            return new Object[]{500, "text/plain", new ByteArrayInputStream(e.getMessage().getBytes())};
        }
    }

    private static String searchVideoUrl(String title, int episode) {
        // 优先搜索 360 影视，匹配率最高
        try {
            String searchUrl = "https://api.so.360kan.com/index?force_v=1&kw=" + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            String json = OkHttp.string(searchUrl);
            JsonObject data = Json.safeObject(json).getAsJsonObject("data");
            JsonArray rows = data.getAsJsonObject("longData").getAsJsonArray("rows");
            
            for (JsonElement el : rows) {
                JsonObject row = el.getAsJsonObject();
                String rowTitle = row.get("titleTxt").getAsString();
                if (!rowTitle.contains(title) && !title.contains(rowTitle)) continue;

                if ("电影".equals(row.get("cat_name").getAsString())) {
                    JsonObject playlinks = row.getAsJsonObject("playlinks");
                    if (playlinks.has("qq")) return cleanUrl(playlinks.get("qq").getAsString());
                    if (playlinks.has("qiyi")) return cleanUrl(playlinks.get("qiyi").getAsString());
                } else {
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episode) {
                        return cleanUrl(series.get(episode - 1).getAsJsonObject().get("url").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String cleanUrl(String url) {
        return url.contains("?") ? url.split("\\?")[0] : url;
    }

    private static String fetchAndConvert(String videoUrl) {
        for (String source : DANMU_SOURCES) {
            try {
                String api = source.replace("{url}", URLEncoder.encode(videoUrl, "UTF-8"));
                String res = OkHttp.string(api);
                if (res.contains("<d")) return res; // 如果已经是XML直接返回
                
                // 处理 JSON 格式弹幕
                JsonObject json = Json.safeObject(res);
                JsonArray danmuku = json.has("danmuku") ? json.getAsJsonArray("danmuku") : 
                                   json.getAsJsonObject("data").getAsJsonArray("danmuku");
                
                if (danmuku != null) {
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>\n");
                    for (JsonElement d : danmuku) {
                        JsonArray item = d.getAsJsonArray();
                        String content = item.get(4).getAsString();
                        if (content.matches(AD_PATTERN)) continue;
                        
                        String time = item.get(0).getAsString();
                        String color = item.get(3).getAsString();
                        xml.append(String.format("<d p=\"%s,1,25,%s\">%s</d>\n", time, color, escape(content)));
                    }
                    xml.append("</i>");
                    return xml.toString();
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
