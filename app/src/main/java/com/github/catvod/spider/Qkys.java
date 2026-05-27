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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        SpiderDebug.log("=== [1. 开始解析] URL: " + playUrl);

        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; SKW-A0 Build/SKW-A0211011CN00MP8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Referer", host + "/");

        // --- 第一步：获取 Cookie ---
        SpiderDebug.log("=== [2. 第一次请求] 尝试获取 Session...");
        OkResult res1 = OkHttp.get(playUrl, null, headers);
        
        // 手动从 Headers 提取 Set-Cookie
        String cookieStr = "";
        Map<String, List<String>> respHeaders = res1.getHeaders();
        if (respHeaders != null && respHeaders.containsKey("set-cookie")) {
            List<String> cookies = respHeaders.get("set-cookie");
            StringBuilder sb = new StringBuilder();
            for (String c : cookies) {
                sb.append(c.split(";")[0]).append("; ");
            }
            cookieStr = sb.toString();
            SpiderDebug.log("=== [调试] 成功提取 Cookie: " + cookieStr);
        } else {
            SpiderDebug.log("=== [警告] 第一次请求未返回 Cookie");
        }

        // --- 第二步：带上 Cookie 进行第二次请求 ---
        if (!cookieStr.isEmpty()) {
            headers.put("Cookie", cookieStr);
        }
        
        // 关键：模拟 JS 跳转的等待时间
        Thread.sleep(500); 

        SpiderDebug.log("=== [3. 第二次请求] 正在带 Cookie 访问...");
        String html = OkHttp.string(playUrl, headers);

        // --- 第三步：校验源码 ---
        if (html == null || !html.contains("player_aaaa")) {
            SpiderDebug.log("=== [错误] 依然没拿到数据，准备打印源码");
            if (html != null) {
                String snippet = html.length() > 500 ? html.substring(0, 500) : html;
                SpiderDebug.log("=== [源码检视] \n" + snippet.replace("<", "&lt;").replace(">", "&gt;"));
            }
            return Result.get().url("").string();
        }

        SpiderDebug.log("=== [4. 源码成功] 长度: " + html.length());

        // --- 第四步：提取 JSON (保持之前的稳定逻辑) ---
        JsonObject pdata;
        try {
            String marker = "var player_aaaa=";
            int start = html.indexOf(marker) + marker.length();
            int jsonStart = html.indexOf("{", start);
            int jsonEnd = html.indexOf("</script>", jsonStart);
            if (jsonEnd == -1) jsonEnd = html.indexOf(";", jsonStart);

            String jsonStr = html.substring(jsonStart, jsonEnd).trim();
            if (jsonStr.endsWith(";")) jsonStr = jsonStr.substring(0, jsonStr.length() - 1);
            pdata = JsonParser.parseString(jsonStr).getAsJsonObject();
            SpiderDebug.log("=== [5. JSON提取成功]");
        } catch (Exception e) {
            SpiderDebug.log("=== [错误] JSON解析失败: " + e.getMessage());
            return Result.get().url("").string();
        }

        // --- 第五步：解析中转页并请求接口 ---
        String pUrl = pdata.get("url").getAsString();
        String pFrom = pdata.get("from").getAsString();
        String pNext = pdata.has("link_next") ? pdata.get("link_next").getAsString() : "";
        String pPlayData = pdata.has("play_data") ? pdata.get("play_data").getAsString() : "";

        String fullIdxLink = jxHost + "/index.php"
                + "?url=" + URLEncoder.encode(pUrl, "UTF-8")
                + "&type=" + URLEncoder.encode(pFrom, "UTF-8")
                + "&next=" + URLEncoder.encode(pNext, "UTF-8")
                + "&data=" + URLEncoder.encode(pPlayData, "UTF-8");

        // 中转页请求也必须带上这个 Headers 和 Cookie
        String idxHtml = OkHttp.string(fullIdxLink, headers);
        String vUrl = extractFromConfig("url", idxHtml);
        String vTime = extractFromConfig("time", idxHtml);
        String vKey = extractFromConfig("vkey", idxHtml);

        if (vUrl.isEmpty()) {
            SpiderDebug.log("=== [错误] 中转页解析失败");
            return Result.get().url("").string();
        }

        // --- 第六步：最终 POST ---
        Map<String, String> apiPayload = new HashMap<>();
        apiPayload.put("url", vUrl);
        apiPayload.put("time", vTime);
        apiPayload.put("vkey", vKey);
        
        try {
            OkResult apiRes = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, headers);
            String apiResp = apiRes.getBody();
            SpiderDebug.log("=== [6. 接口响应] " + apiResp);

            if (apiResp != null && !apiResp.isEmpty()) {
                JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
                String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : "";
                if (!finalUrl.isEmpty()) {
                    SpiderDebug.log("=== [7. 解析成功] URL: " + finalUrl);
                    return Result.get().url(finalUrl).header(headers).string();
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("=== [错误] API请求崩溃: " + e.getMessage());
        }

        return Result.get().url("").string();
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
