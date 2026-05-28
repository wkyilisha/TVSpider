package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TvDy extends Spider {

    private final String host = "http://www.viptvb08.com";
    
    private static final Map<String, String> jiexiUrlMap = new HashMap<>();
    static {
        jiexiUrlMap.put("lzm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("bfzym3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytvb", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("YYNB", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("ffm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("1080zyk", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytv", "http://111.229.219.148:808/index.php?url=");
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.104 Mobile Safari/537.36");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        String[][] categoryList = {{"电影", "1"}, {"电视剧", "2"}, {"综艺", "3"}, {"短剧", "5"}};
        for (String[] cls : categoryList) classes.add(new Class(cls[1], cls[0]));
        // 【修复点1】明确指定 null 的类型为 JSONObject
        return Result.string(classes, (JSONObject) null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String targetUrl = host + "/vod/show/id/" + tid + "/page/" + pg + ".html";
        String html = OkHttp.string(targetUrl, getHeaders());
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        Elements items = doc.select(".myui-vodlist li");
        for (Element item : items) {
            Element a = item.selectFirst("a.myui-vodlist__thumb");
            if (a == null) continue;
            Vod vod = new Vod();
            vod.setVodId(a.attr("href"));
            vod.setVodName(a.attr("title"));
            vod.setVodPic(a.attr("data-original"));
            vod.setVodRemarks(item.select(".pic-tag").text());
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        String html = OkHttp.string(detailUrl, getHeaders());
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(doc.select("h1.title").text());
        vod.setVodPic(doc.selectFirst(".myui-content__thumb img").attr("data-original"));
        vod.setVodContent(doc.select(".sketch.content").text().trim());

        Elements playPanels = doc.select(".myui-panel-bg");
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (Element panel : playPanels) {
            Element head = panel.selectFirst(".myui-panel__head h3.title");
            if (head == null || !head.text().contains("线路")) continue;
            Elements nameUrls = panel.select("ul.myui-content__list a");
            if (nameUrls.isEmpty()) continue;
            List<String> urls = new ArrayList<>();
            for (Element urlItem : nameUrls) urls.add(urlItem.text() + "$" + urlItem.attr("href"));
            
            fromList.add(head.text().trim());
            // 【修复点2】改用 TextUtils.join
            urlList.add(TextUtils.join("#", urls));
        }

        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        HashMap<String, String> currentHeaders = getHeaders();

        try {
            OkResult cookieRes = OkHttp.get(playUrl, null, currentHeaders);
            Map<String, List<String>> respHeaders = cookieRes.getResp();
            if (respHeaders != null && respHeaders.containsKey("set-cookie")) {
                List<String> cookies = respHeaders.get("set-cookie");
                StringBuilder sb = new StringBuilder();
                for (String c : cookies) sb.append(c.split(";")[0]).append("; ");
                currentHeaders.put("Cookie", sb.toString());
            }

            String html = OkHttp.string(playUrl, currentHeaders);
            String marker = "var player_data=";
            int start = html.indexOf(marker) + marker.length();
            int end = html.indexOf("</script>", start);
            String jsonStr = html.substring(start, end).trim();

            JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
            String rawUrl = playerData.get("url").getAsString();
            String from = playerData.get("from").getAsString();

            if (jiexiUrlMap.containsKey(from)) {
                String fullApiUrl = jiexiUrlMap.get(from) + URLEncoder.encode(rawUrl, "UTF-8");
                String apiResponse = OkHttp.string(fullApiUrl, currentHeaders);
                if (apiResponse != null && !apiResponse.isEmpty()) {
                    JsonObject resJson = JsonParser.parseString(apiResponse).getAsJsonObject();
                    if (resJson.has("code") && resJson.get("code").getAsInt() == 200) {
                        String realUrl = resJson.get("url").getAsString();
                        Map<String, String> pureHeaders = new HashMap<>();
                        pureHeaders.put("User-Agent", currentHeaders.get("User-Agent"));
                        return Result.get().url(realUrl).parse(0).header(pureHeaders).string();
                    }
                }
            }
            return Result.get().url(rawUrl).parse(0).header(new HashMap<>()).string();
        } catch (Exception e) {
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String searchUrl = host + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
        String jsonResult = OkHttp.string(searchUrl, getHeaders());
        List<Vod> list = new ArrayList<>();
        if (jsonResult != null && !jsonResult.isEmpty()) {
            JsonObject response = JsonParser.parseString(jsonResult).getAsJsonObject();
            if (response.has("code") && response.get("code").getAsInt() == 1) {
                JsonArray jsonArray = response.getAsJsonArray("list");
                for (JsonElement element : jsonArray) {
                    JsonObject item = element.getAsJsonObject();
                    Vod vod = new Vod();
                    vod.setVodId("/vod/detail/id/" + item.get("id").getAsInt() + ".html");
                    vod.setVodName(item.get("name").getAsString());
                    vod.setVodPic(item.get("pic").getAsString());
                    list.add(vod);
                }
            }
        }
        return Result.string(list);
    }
}
