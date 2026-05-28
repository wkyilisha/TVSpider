package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TvDy extends Spider {

    private final String host = "http://www.viptvb08.com";
    
    // 初始化解析映射表
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
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; SKW-A0 Build/SKW-A0211011CN00MP8) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        String[][] categoryList = {
                {"电影", "1"},
                {"电视剧", "2"},
                {"综艺", "3"},
                {"短剧", "5"}
        };
        for (String[] cls : categoryList) {
            classes.add(new Class(cls[1], cls[0]));
        }
        // 使用 Fongmi 专属的 Result 实体类进行构建
        return Result.string(classes, null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String targetUrl = host + "/vod/show/id/" + tid + "/page/" + pg + ".html";
        String html = OkHttp.string(targetUrl, getHeaders());
        Document doc = Jsoup.parse(html);
        
        List<Vod> list = new ArrayList<>();
        Elements items = doc.select("ul.myui-vodlist clearfix > li, .myui-vodlist li");
        
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
        vod.setVodYear(doc.select(".data:contains(年份)").text().replaceAll(".*年份：", "").trim());
        vod.setVodArea(doc.select(".data:contains(地区)").text().replaceAll(".*地区：", "").trim());
        vod.setVodActor(doc.select(".data:contains(主演)").text().replaceAll(".*主演：", "").trim());
        vod.setVodDirector(doc.select(".data:contains(导演)").text().replaceAll(".*导演：", "").trim());
        vod.setVodContent(doc.select(".sketch.content").text().trim());

        // 解析播放线路与集数
        Elements playPanels = doc.select(".myui-panel-bg");
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (Element panel : playPanels) {
            Element head = panel.selectFirst(".myui-panel__head h3.title");
            if (head == null || !head.text().contains("线路")) continue;

            String fromName = head.text().trim();
            Elements nameUrls = panel.select("ul.myui-content__list a");
            if (nameUrls.isEmpty()) continue;

            List<String> urls = new ArrayList<>();
            for (Element urlItem : nameUrls) {
                urls.add(urlItem.text() + "$" + urlItem.attr("href"));
            }
            
            fromList.add(fromName);
            urlList.add(Util.join("#", urls));
        }

        vod.setVodPlayFrom(Util.join("$$$", fromList));
        vod.setVodPlayUrl(Util.join("$$$", urlList));

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        HashMap<String, String> currentHeaders = getHeaders();

        try {
            // 1. 获取安全 Cookie 权限
            com.github.catvod.net.OkResult cookieRes = OkHttp.get(playUrl, null, currentHeaders);
            Map<String, List<String>> respHeaders = cookieRes.getResp();
            if (respHeaders != null && respHeaders.containsKey("set-cookie")) {
                List<String> cookies = respHeaders.get("set-cookie");
                StringBuilder sb = new StringBuilder();
                for (String c : cookies) sb.append(c.split(";")[0]).append("; ");
                currentHeaders.put("Cookie", sb.toString());
            }

            Thread.sleep(300);

            // 2. 请求播放页源码并截取 player_data
            String html = OkHttp.string(playUrl, currentHeaders);
            if (html == null || !html.contains("player_data")) {
                throw new Exception("未发现播放配置数据");
            }

            String marker = "var player_data=";
            int start = html.indexOf(marker) + marker.length();
            int end = html.indexOf("</script>", start);
            String jsonStr = html.substring(start, end).trim();

            JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
            String rawUrl = playerData.get("url").getAsString();
            String from = playerData.get("from").getAsString();

            // 3. 判断是否走第三方中转接口解析
            if (jiexiUrlMap.containsKey(from)) {
                String apiBase = jiexiUrlMap.get(from);
                String fullApiUrl = apiBase + URLEncoder.encode(rawUrl, "UTF-8");
                
                String apiResponse = OkHttp.string(fullApiUrl, currentHeaders);
                if (apiResponse != null && !apiResponse.isEmpty()) {
                    JsonObject resJson = JsonParser.parseString(apiResponse).getAsJsonObject();
                    if (resJson.has("code") && resJson.get("code").getAsInt() == 200 && resJson.has("url")) {
                        String realMediaUrl = resJson.get("url").getAsString();

                        // 【核心要求1】：重新定义播放头，彻底拿掉 Referer 
                        Map<String, String> purePlayHeaders = new HashMap<>();
                        purePlayHeaders.put("User-Agent", currentHeaders.get("User-Agent"));

                        // 使用 Fongmi 规范的 Result.get() 返回直链播放 (parse: 0)
                        return Result.get().url(realMediaUrl).parse(0).header(purePlayHeaders).string();
                    }
                }
            } else {
                // 如果是直链，不经解析中心直接播放，同样拿掉 Referer
                Map<String, String> purePlayHeaders = new HashMap<>();
                purePlayHeaders.put("User-Agent", currentHeaders.get("User-Agent"));
                return Result.get().url(rawUrl).parse(0).header(purePlayHeaders).string();
            }

            throw new Exception("接口解析未返回有效媒体直链");

        } catch (Exception e) {
            // 【核心要求2】：失败或者解析不出时，降级以 parse: 1 (嗅探模式) 扔给壳子
            // 注意：嗅探时把当前的 headers（包含 Cookie 传入），以提高网页在壳子里加载时的过盾成功率
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 请求后台 AJAX 智能建议搜索接口
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
                    // 补全详情页相对路径 ID
                    vod.setVodId("/vod/detail/id/" + item.get("id").getAsInt() + ".html");
                    vod.setVodName(item.get("name").getAsString());
                    vod.setVodPic(item.get("pic").getAsString());
                    vod.setVodRemarks("搜索结果");
                    list.add(vod);
                }
            }
        }
        
        return Result.string(list);
    }
}

public class TvDy extends Spider {

    private static final String siteUrl = "http://www.viptvb08.com";
    private static final String cateUrl = siteUrl + "/search.php?tid=";
    private static final String detailUrl = siteUrl + "/movie/";
    private static final String searchUrl = siteUrl + "/search.php?searchword=";
    private static final String playUrl = siteUrl + "/play/";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        String[] typeIdList = {"1", "2", "3", "4", "5", "34"};
        String[] typeNameList = {"电影", "电视剧", "综艺", "动漫", "福利", "纪录片"};
        for (int i = 0; i < typeNameList.length; i++) {
            classes.add(new Class(typeIdList[i], typeNameList[i]));
        }
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        for (Element element : doc.select("a.stui-vodlist__thumb")) {
            try {
                String pic = element.attr("data-original");
                String url = element.attr("href");
                String name = element.attr("title");
                if (!pic.startsWith("http")) {
                    pic = siteUrl + pic;
                }
                String id = url.split("/")[2];
                list.add(new Vod(id, name, pic));
            } catch (Exception e) {

            }
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String target = cateUrl + tid + "&searchtype=5&order=commend&page=" + pg;
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        for (Element element : doc.select("div.stui-vodlist__box a")) {
            try {
                String pic = element.attr("data-original");
                String url = element.attr("href");
                String name = element.attr("title");
                if (!pic.startsWith("http")) {
                    pic = siteUrl + pic;
                }
                String id = url.split("/")[2];
                list.add(new Vod(id, name, pic));
            } catch (Exception e) {

            }
        }
        Integer total = (Integer.parseInt(pg) + 1) * 20;
        return Result.string(Integer.parseInt(pg), Integer.parseInt(pg) + 1, 20, total, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Document doc = Jsoup.parse(OkHttp.string(detailUrl.concat(ids.get(0)), getHeaders()));
        String name = doc.select("h1.title").text();
        String pic = doc.select("a.pic img").attr("data-original");
        String year = doc.select("p.data").get(4).text().replace("年份：","");
        String desc = doc.select("span.detail-content").text();

        // 播放源
        Elements tabs = doc.select("div.stui-vodlist__head h4");
        Elements list = doc.select("div.stui-vodlist__head ul");
        String PlayFrom = "";
        String PlayUrl = "";
        for (int i = 0; i < tabs.size(); i++) {
            String tabName = tabs.get(i).text();
            if (!"".equals(PlayFrom)) {
                PlayFrom = PlayFrom + "$$$" + tabName;
            } else {
                PlayFrom = PlayFrom + tabName;
            }
            Elements li = list.get(i).select("a");
            String liUrl = "";
            for (int i1 = 0; i1 < li.size(); i1++) {
                if (!"".equals(liUrl)) {
                    liUrl = liUrl + "#" + li.get(i1).text() + "$" + li.get(i1).attr("href").replace("/play/", "");
                } else {
                    liUrl = liUrl + li.get(i1).text() + "$" + li.get(i1).attr("href").replace("/play/", "");
                }
            }
            if (!"".equals(PlayUrl)) {
                PlayUrl = PlayUrl + "$$$" + liUrl;
            } else {
                PlayUrl = PlayUrl + liUrl;
            }
        }

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPic(siteUrl + pic);
        vod.setVodYear(year);
        vod.setVodName(name);
        vod.setVodContent(desc);
        vod.setVodPlayFrom(PlayFrom);
        vod.setVodPlayUrl(PlayUrl);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(OkHttp.string(searchUrl.concat(URLEncoder.encode(key)), getHeaders()));
        for (Element element : doc.select("div.stui-vodlist__box a")) {
            try {
                String pic = element.attr("data-original");
                String url = element.attr("href");
                String name = element.attr("title");
                if (!pic.startsWith("http")) {
                    pic = siteUrl + pic;
                }
                String id = url.split("/")[2];
                list.add(new Vod(id, name, pic));
            } catch (Exception e) {

            }
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Document doc = Jsoup.parse(OkHttp.string(playUrl.concat(id), getHeaders()));
        String regex = "var now=base64decode(.*?);var";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(doc.html());
        String url = doc.html();
        if (matcher.find()) {
            url = decodeBase64(matcher.group(1).replace("(\\\"","").replace("\\\")",""));
        }
        return Result.get().url(url).header(getHeaders()).string();
    }

    public static String decodeBase64(String encodedString) {
        return new String(Base64.decode(encodedString, Base64.DEFAULT));
    }
}
