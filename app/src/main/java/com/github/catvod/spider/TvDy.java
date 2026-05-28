package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter;
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
import java.util.Arrays;
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
        // 順序：電視劇(含國產劇)放在第一位
        classes.add(new Class("2", "電視劇"));
        classes.add(new Class("1", "電影"));
        classes.add(new Class("3", "綜藝"));
        classes.add(new Class("5", "短劇"));

        Result result = new Result().classes(classes);
        if (filter) {
            result.filters(getFilterConfig());
        }
        return result.toString();
    }

    @Override
    protected String getFilterConfig() {
        LinkedHashMap<String, List<Filter>> filterConfig = new LinkedHashMap<>();

        // --- 1. 電視劇 (ID: 2) ---
        List<Filter> tvFilters = new ArrayList<>();
        tvFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("國產劇", "13"),
                new Filter.Value("全部", "2"),
                new Filter.Value("港台劇", "14"),
                new Filter.Value("日韓劇", "15"),
                new Filter.Value("歐美劇", "16"),
                new Filter.Value("海外劇", "20")
        )));
        tvFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("古裝", "古裝"), new Filter.Value("戰爭", "戰爭"),
                new Filter.Value("青春偶像", "青春偶像"), new Filter.Value("喜劇", "喜劇"), new Filter.Value("家庭", "家庭"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("動作", "動作"), new Filter.Value("奇幻", "奇幻"),
                new Filter.Value("劇情", "劇情"), new Filter.Value("歷史", "歷史"), new Filter.Value("經典", "經典")
        )));
        tvFilters.add(new Filter("area", "地區", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陸", "大陸"), new Filter.Value("香港", "香港"),
                new Filter.Value("韓國", "韓國"), new Filter.Value("台灣", "台灣"), new Filter.Value("日本", "日本"),
                new Filter.Value("美國", "美國"), new Filter.Value("泰國", "泰國"), new Filter.Value("英國", "英國"),
                new Filter.Value("新加坡", "新加坡"), new Filter.Value("其他", "其他")
        )));
        tvFilters.add(new Filter("year", "年份", getYearValues()));
        tvFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("2", tvFilters);

        // --- 2. 電影 (ID: 1) ---
        List<Filter> movieFilters = new ArrayList<>();
        movieFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("全部", "1"),
                new Filter.Value("動作片", "6"), new Filter.Value("喜劇片", "7"), new Filter.Value("愛情片", "8"),
                new Filter.Value("科幻片", "9"), new Filter.Value("劇情片", "10"), new Filter.Value("恐怖片", "11"),
                new Filter.Value("戰爭片", "12")
        )));
        movieFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("喜劇", "喜劇"), new Filter.Value("愛情", "愛情"),
                new Filter.Value("恐怖", "恐怖"), new Filter.Value("動作", "動作"), new Filter.Value("科幻", "科幻"),
                new Filter.Value("劇情", "劇情"), new Filter.Value("戰爭", "戰爭"), new Filter.Value("警匪", "警匪"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("動畫", "動畫"), new Filter.Value("奇幻", "奇幻"),
                new Filter.Value("武俠", "武俠"), new Filter.Value("冒險", "冒險"), new Filter.Value("懸疑", "懸疑")
        )));
        movieFilters.add(new Filter("area", "地區", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陸", "大陸"), new Filter.Value("香港", "香港"),
                new Filter.Value("台灣", "台灣"), new Filter.Value("美國", "美國"), new Filter.Value("法國", "法國"),
                new Filter.Value("英國", "英國"), new Filter.Value("日本", "日本"), new Filter.Value("韓國", "韓國"),
                new Filter.Value("德國", "德國"), new Filter.Value("泰國", "泰國"), new Filter.Value("印度", "印度")
        )));
        movieFilters.add(new Filter("year", "年份", getYearValues()));
        movieFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("1", movieFilters);

        // --- 3. 綜藝 (ID: 3) ---
        List<Filter> varietyFilters = new ArrayList<>();
        varietyFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("全部", "3"),
                new Filter.Value("大陸綜藝", "21"), new Filter.Value("香港綜藝", "22"),
                new Filter.Value("日韓綜藝", "23"), new Filter.Value("歐美綜藝", "24")
        )));
        varietyFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("選秀", "選秀"), new Filter.Value("情感", "情感"),
                new Filter.Value("訪談", "訪談"), new Filter.Value("旅遊", "旅遊"), new Filter.Value("音樂", "音樂"),
                new Filter.Value("美食", "美食"), new Filter.Value("紀實", "紀實"), new Filter.Value("遊戲", "遊戲")
        )));
        varietyFilters.add(new Filter("year", "年份", getYearValues()));
        varietyFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("3", varietyFilters);

        // --- 5. 短劇 (ID: 5) ---
        List<Filter> shortFilters = new ArrayList<>();
        shortFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("喜劇", "喜劇"), new Filter.Value("愛情", "愛情"),
                new Filter.Value("動作", "動作"), new Filter.Value("古裝", "古裝"), new Filter.Value("都市", "都市"),
                new Filter.Value("懸疑", "懸疑"), new Filter.Value("玄幻", "玄幻")
        )));
        shortFilters.add(new Filter("year", "年份", getYearValues()));
        shortFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("5", shortFilters);

        return new Gson().toJson(filterConfig);
    }

    private List<Filter.Value> getYearValues() {
        List<Filter.Value> values = new ArrayList<>();
        values.add(new Filter.Value("全部", ""));
        for (int i = 2026; i >= 2001; i--) {
            values.add(new Filter.Value(String.valueOf(i), String.valueOf(i)));
        }
        return values;
    }

    private List<Filter.Value> getSortValues() {
        return Arrays.asList(
                new Filter.Value("時間", "time"),
                new Filter.Value("人氣", "hits"),
                new Filter.Value("評分", "score")
        );
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String id = extend.containsKey("id") ? extend.get("id") : tid;
            StringBuilder sb = new StringBuilder();
            sb.append(host).append("/vod/show");

            // 嚴格按照網站 URL 路徑順序拼接
            String[] keys = {"area", "by", "class", "lang", "year"};
            for (String key : keys) {
                if (extend.containsKey(key) && !extend.get(key).isEmpty()) {
                    sb.append("/").append(key).append("/").append(URLEncoder.encode(extend.get(key), "UTF-8"));
                }
            }
            sb.append("/id/").append(id).append("/page/").append(pg).append(".html");

            String html = OkHttp.string(sb.toString(), getHeaders());
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select(".myui-vodlist li");
            for (Element item : items) {
                Vod vod = new Vod();
                Element a = item.selectFirst("a.myui-vodlist__thumb");
                vod.setVodId(a.attr("href"));
                vod.setVodName(a.attr("title"));
                vod.setVodPic(a.attr("data-original"));
                vod.setVodRemarks(item.select(".pic-tag").text());
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        String html = OkHttp.string(detailUrl, getHeaders());
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        // 定位標題
        vod.setVodName(doc.select("h1.title").text().trim());
        // 定位圖片
        vod.setVodPic(doc.selectFirst(".myui-content__thumb img").attr("data-original"));
        
        // --- 精準定位開始 ---
        
        // 1. 年份：定位包含“年份：”的 p.data 標籤下的 a 標籤文字
        vod.setVodYear(doc.select("p.data:contains(年份) a").text().trim());
        
        // 2. 地區：定位包含“地區：”的 p.data 標籤下的 a 標籤文字
        vod.setVodArea(doc.select("p.data:contains(地區) a").text().trim());
        
        // 3. 主演：提取所有主演 a 標籤的文字，並用逗號連接
        Elements actors = doc.select("p.data:contains(主演) a");
        List<String> actorList = new ArrayList<>();
        for (Element a : actors) actorList.add(a.text());
        vod.setVodActor(TextUtils.join(", ", actorList));
        
        // 4. 導演：提取導演 a 標籤文字
        vod.setVodDirector(doc.select("p.data:contains(導演) a").text().trim());
        
        // 5. 更新備註：提取包含“更新：”標籤後的紅色文字內容
        vod.setVodRemarks(doc.select("p.data:contains(更新) .text-red").text().trim());
        
        // 6. 簡介：定位劇情簡介面板下的全量內容（排除隱藏屬性）
        Element contentEl = doc.selectFirst(".col-pd.text-collapse.content .data");
        if (contentEl != null) {
            vod.setVodContent(contentEl.text().trim());
        } else {
            vod.setVodContent(doc.select(".sketch.content").text().trim());
        }

        // --- 播放線路解析 ---
        Elements playPanels = doc.select(".myui-panel-bg");
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (Element panel : playPanels) {
            // 只抓取包含播放列表的面板
            Element head = panel.selectFirst(".myui-panel__head h3.title");
            if (head == null) continue;
            
            String fromName = head.text().trim();
            // 過濾掉“劇情簡介”等非播放線路面板
            if (fromName.contains("劇情") || fromName.contains("猜你喜歡")) continue;

            Elements nameUrls = panel.select("ul.myui-content__list a");
            if (nameUrls.isEmpty()) continue;
            
            List<String> urls = new ArrayList<>();
            for (Element urlItem : nameUrls) {
                // 格式：第01集$播放地址
                urls.add(urlItem.text() + "$" + urlItem.attr("href"));
            }
            
            fromList.add(fromName);
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
