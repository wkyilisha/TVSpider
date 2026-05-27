package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.catvod.net.OkResult;
import com.github.catvod.crawler.SpiderDebug;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Connection", "keep-alive");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("guochan", "国产剧"));
        classes.add(new Class("2", "连续剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("4", "动漫"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.get().page(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));

        Element titleElem = doc.selectFirst(".stui-content__detail .title");
        if (titleElem != null) vod.setVodName(titleElem.text().trim());

        Element thumbImg = doc.selectFirst(".stui-content__thumb img");
        if (thumbImg != null) {
            String pic = thumbImg.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumbImg.attr("src");
            vod.setVodPic(pic);
        }
        Element picText = doc.selectFirst(".stui-content__thumb .pic-text");
        if (picText != null) vod.setVodRemarks(picText.text().trim());

        Element dirElem = doc.selectFirst(".stui-content__detail p.data:contains(导演)");
        if (dirElem != null) vod.setVodDirector(dirElem.text().replace("导演：", "").trim());

        Element actElem = doc.selectFirst(".stui-content__detail p.data:contains(主演)");
        if (actElem != null) vod.setVodActor(actElem.text().replace("主演：", "").trim());

        Element typeElem = doc.selectFirst(".stui-content__detail p.data:contains(类型)");
        if (typeElem != null) vod.setTypeName(typeElem.text().replace("类型：", "").trim());

        Element areaElem = doc.selectFirst(".stui-content__detail p.data:contains(地区)");
        if (areaElem != null) vod.setVodArea(areaElem.text().replace("地区：", "").trim());

        Element yearElem = doc.selectFirst(".stui-content__detail p.data:contains(年份)");
        if (yearElem != null) vod.setVodYear(yearElem.text().replace("年份：", "").trim());

        Element descElem = doc.selectFirst(".stui-content__desc");
        if (descElem != null) vod.setVodContent(descElem.text().trim());

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (Element head : doc.select(".stui-pannel__head")) {
            String title = head.select("h3.title").text();
            if (title.contains("源") || title.contains("播放")) {
                fromList.add(title);
                Elements as = head.parent().select("ul.stui-content__playlist a");
                List<String> links = new ArrayList<>();
                for (Element a : as) {
                    String href = a.attr("href");
                    if (!href.startsWith("http")) href = host + href;
                    links.add(a.text() + "$" + href);
                }
                urlList.add(String.join("#", links));
            }
        }
        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.string(list);
    }

    // === 根据 Python 逻辑深度重构的视频解析部分 ===
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        SpiderDebug.step("1. 开始解析播放页", "URL: " + playUrl);

        HashMap<String, String> h1 = getHeaders();
        h1.put("Referer", host + "/");

        String html = "";
        for (int i = 0; i < 3; i++) {
            try {
                html = OkHttp.string(playUrl, h1);
                if (html.contains("player_aaaa")) break;
                Thread.sleep(1000);
            } catch (Exception e) {
                SpiderDebug.log("播放页请求重试 - 次数: " + (i + 1));
            }
        }

        if (!html.contains("player_aaaa")) {
            SpiderDebug.step("错误", "源码中未找到 player_aaaa，可能是被防火墙拦截或页面改版");
            return Result.get().parse(1).url(playUrl).string();
        }

        // 第一步：提取 player_aaaa
        JsonObject pdata;
        try {
            int start = html.indexOf("var player_aaaa=");
            start = html.indexOf("{", start);
            int end = findJsonEnd(html, start);
            String jsonStr = html.substring(start, end + 1).trim();
            pdata = JsonParser.parseString(jsonStr).getAsJsonObject();
            
            SpiderDebug.step("2. 提取player_aaaa成功", "内容: " + jsonStr);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.get().parse(1).url(playUrl).string();
        }

        String pUrl      = pdata.has("url")       ? pdata.get("url").getAsString()       : "";
        String pFrom     = pdata.has("from")       ? pdata.get("from").getAsString()      : "";
        String pNext     = pdata.has("link_next")  ? pdata.get("link_next").getAsString() : "";
        String pPlayData = pdata.has("play_data")  ? pdata.get("play_data").getAsString() : "";

        if (!pNext.isEmpty()) pNext = "https://www.qkw1.com" + pNext;

        // 第二步：GET 中转页
        String fullIdxLink = jxHost + "/index.php"
                + "?url="  + URLEncoder.encode(pUrl,      "UTF-8")
                + "&type=" + URLEncoder.encode(pFrom,     "UTF-8")
                + "&next=" + URLEncoder.encode(pNext,     "UTF-8")
                + "&data=" + URLEncoder.encode(pPlayData, "UTF-8");
        
        SpiderDebug.step("3. 拼接中转页URL", "完整链接: " + fullIdxLink);

        HashMap<String, String> h2 = new HashMap<>();
        h2.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
        h2.put("Referer", "https://www.qkw1.com/");

        String idxHtml = OkHttp.string(fullIdxLink, h2);
        SpiderDebug.step("4. 获取中转页源码", "长度: " + (idxHtml == null ? 0 : idxHtml.length()));

        String vUrl  = extractFromConfig("url",  idxHtml);
        String vTime = extractFromConfig("time", idxHtml);
        String vKey  = extractFromConfig("vkey", idxHtml);
        
        SpiderDebug.step("5. 解析Config结果", "vUrl: " + vUrl + " | vTime: " + vTime + " | vKey: " + vKey);

        if (vUrl.isEmpty() || vTime.isEmpty()) {
            SpiderDebug.step("错误", "中转页参数提取失败，请检查 extractFromConfig 正则逻辑");
            return Result.get().parse(1).url(playUrl).string();
        }

        // 第三步：POST mizhi_json.php
        Map<String, String> apiPayload = new HashMap<>();
        apiPayload.put("url",  vUrl);
        apiPayload.put("time", vTime);
        apiPayload.put("key",  "");
        apiPayload.put("vkey", vKey);

        HashMap<String, String> apiHeaders = new HashMap<>();
        apiHeaders.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; Redmi K30 5G Build/SKQ1.211006.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36");
        apiHeaders.put("Referer", fullIdxLink);
        apiHeaders.put("Origin", jxHost);
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");

        try {
            SpiderDebug.step("6. 发起最终API请求", "Payload: " + apiPayload.toString());
            OkResult apiRes  = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, apiHeaders);
            String   apiResp = apiRes.getBody();
            SpiderDebug.step("7. API响应内容", "Response: " + apiResp);

            if (!apiResp.isEmpty()) {
                JsonObject resJson  = JsonParser.parseString(apiResp).getAsJsonObject();
                String     finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : "";

                if (!finalUrl.isEmpty()) {
                    SpiderDebug.step("8. 解析成功", "最终播放地址: " + finalUrl);
                    return Result.get().url(finalUrl).string();
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }

        return Result.get().parse(1).url(playUrl).string();
    }


    private int findJsonEnd(String text, int start) {
    int count = 0;
    for (int i = start; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '{') {
            count++;
        } else if (c == '}') {
            count--;
            if (count == 0) return i;
        }
    }
    return text.length() - 1;
}

    private String extractFromConfig(String name, String text) {
    if (text == null || text.isEmpty()) return "";

    int configStart = text.indexOf("var config");
    if (configStart == -1) return "";

    int braceStart = text.indexOf("{", configStart);
    if (braceStart == -1) return "";

    int braceEnd = findJsonEnd(text, braceStart);
    String configBlock = text.substring(braceStart, braceEnd + 1);

    String[] patterns = {
        "\"" + name + "\"\\s*:\\s*\"([^\"]*)\"",
        "'" + name + "'\\s*:\\s*'([^']*)'",
        "\"" + name + "\"\\s*:\\s*(\\d+)"
    };

    for (String pat : patterns) {
        Matcher m = Pattern.compile(pat).matcher(configBlock);
        if (m.find()) return m.group(1).trim();
    }
    return "";
}

    private String urlEncodeParams(Map<String, String> params) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : params.entrySet()) {
        if (sb.length() > 0) sb.append("&");
        sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
          .append("=")
          .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
    }
    return sb.toString();
}
}
