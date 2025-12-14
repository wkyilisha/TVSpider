package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult; // 关键：导入OkResult类
import com.github.catvod.utils.Notify;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    // 修复：去掉末尾斜杠，避免URL拼接双斜杠
    private final String siteUrl = "https://m.87kkt.com";

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/100.0.4896.77 Mobile/15E148 Safari/604.1");
        header.put("Connection", "keep-alive");
        header.put("Referer", siteUrl + "/");
        header.put("sec-fetch-dest", "iframe");
        header.put("sec-fetch-mode", "navigate");
        header.put("sec-fetch-site", "cross-site");
        return header;
    }

    private Map<String, String> getVideoHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("Accept", "*/*");
        header.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,zh-TW;q=0.7,de;q=0.6");
        header.put("Cache-Control", "no-cache");
        header.put("Connection", "keep-alive");
        header.put("Pragma", "no-cache");
        header.put("Sec-Fetch-Dest", "video");
        header.put("Sec-Fetch-Mode", "no-cors");
        header.put("Sec-Fetch-Site", "cross-site");
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        return header;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        Document doc = Jsoup.parse(OkHttp.string(siteUrl));

        // 提取分类（容错：空值跳过）
        for (Element li : doc.select(".stui-header__menu > li")) {
            String href = li.select("a").attr("href");
            String text = li.select("a").text();
            if (StringUtils.isNotEmpty(href) && StringUtils.isNotEmpty(text)) {
                classes.add(new Class(href, text));
            }
        }

        getVods(list, doc);
        return Result.string(classes, list);
    }

    // 修复：变量名语义化、pic判空用StringUtils、备注提取完整
    private void getVods(List<Vod> list, Document doc) {
        for (Element li : doc.select(".stui-vodlist > li")) {
            String id = li.select(".stui-vodlist__box > a.stui-vodlist__thumb").attr("href");
            String name = li.select(".stui-vodlist__detail > h4.title > a").text();
            String pic = li.select(".stui-vodlist__box > a.stui-vodlist__thumb").attr("data-original");
            
            // 修复：用StringUtils判空，避免NullPointerException
            if (StringUtils.isEmpty(pic)) {
                pic = li.select(".stui-vodlist__box > a.stui-vodlist__thumb > img").attr("src");
            }
            
            // 修复：提取完整备注（分类+更新状态）
            String category = li.select(".stui-vodlist__box > a.stui-vodlist__thumb > span.pic-text1").text();
            String update = li.select(".stui-vodlist__box > a.stui-vodlist__thumb > span.pic-text").text();
            String remark = StringUtils.isEmpty(category) ? update : (category + " " + update);

            // 容错：核心信息为空则跳过
            if (StringUtils.isNotEmpty(id) && StringUtils.isNotEmpty(name)) {
                list.add(new Vod(id, name, pic, remark));
            }
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String[] arr = tid.split("\\.");
        String target = siteUrl + arr[0] + "-" + pg + ".html";
        String html = OkHttp.string(target);
        Document doc = Jsoup.parse(html);
        
        getVods(list, doc);
        
        // 分页处理（默认总页数极大，避免分页异常）
        int page = Integer.parseInt(pg);
        int totalPage = Integer.MAX_VALUE / 12 + 1;
        int total = Integer.MAX_VALUE;
        return Result.get().vod(list).page(page, totalPage, 12, total).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids.isEmpty()) return Result.error("ID为空");
        String detailUrl = siteUrl + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(detailUrl, getHeader()));

        // 提取基础信息
        String title = doc.select(".stui-content__detail > h1.title.wdetail").text();
        String vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("data-original");
        if (StringUtils.isEmpty(vodPic)) {
            vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("src");
        }

        // 解析类型/地区/年份
        String classifyInfo = doc.select(".stui-content__detail > p.data.hidden-xs").text();
        String classifyName = "";
        String vodArea = "";
        String vodYear = "";
        if (StringUtils.isNotEmpty(classifyInfo)) {
            String[] infoParts = classifyInfo.split(" / ");
            for (String part : infoParts) {
                if (part.startsWith("类型：")) classifyName = part.replace("类型：", "");
                if (part.startsWith("地区：")) vodArea = part.replace("地区：", "");
                if (part.startsWith("年份：")) vodYear = part.replace("年份：", "");
            }
        }

        // 提取状态、导演、主演、简介
        String vodRemarks = doc.select(".stui-content__detail > p.data:contains(\"状态：\") > span").text();
        String vodDirector = doc.select(".stui-content__detail > p.data:contains(\"导演：\")").text().replace("导演：", "");
        String vodActor = doc.select(".stui-content__detail > p.data:contains(\"主演：\")").text().replace("主演：", "");
        String briefSketch = doc.select(".detail-sketch").text();
        String briefContent = doc.select(".detail-content").text();
        String vodContent = StringUtils.isEmpty(briefContent) ? briefSketch : (briefSketch + briefContent);

        // 提取多播放源
        StringBuilder vodPlayFrom = new StringBuilder();
        StringBuilder vodPlayUrl = new StringBuilder();
        Elements playSourceHeads = doc.select(".stui-vodlist__head");
        for (Element head : playSourceHeads) {
            String sourceName = head.select("h3.title").text();
            if (StringUtils.isEmpty(sourceName)) continue;
            
            Element playlist = head.nextElementSibling();
            if (playlist == null || !playlist.hasClass("stui-content__playlist")) continue;
            
            Elements episodes = playlist.select("li > a");
            if (episodes.isEmpty()) continue;

            // 拼接播放源名称
            if (vodPlayFrom.length() > 0) vodPlayFrom.append("$$$");
            vodPlayFrom.append(sourceName);

            // 拼接集数链接
            StringBuilder episodeStr = new StringBuilder();
            for (Element episode : episodes) {
                String epName = episode.text();
                String epUrl = episode.attr("href");
                if (StringUtils.isEmpty(epUrl)) continue;
                
                if (episodeStr.length() > 0) episodeStr.append("#");
                episodeStr.append(epName).append("$").append(epUrl);
            }

            // 拼接播放URL
            if (vodPlayUrl.length() > 0) vodPlayUrl.append("$$$");
            vodPlayUrl.append(episodeStr);
        }

        // 封装Vod对象
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(title);
        vod.setVodPic(vodPic);
        vod.setTypeName(classifyName);
        vod.setVodArea(vodArea);
        vod.setVodYear(vodYear);
        vod.setVodRemarks(vodRemarks);
        vod.setVodDirector(vodDirector);
        vod.setVodActor(vodActor);
        vod.setVodContent(vodContent);
        vod.setVodPlayFrom(vodPlayFrom.toString());
        vod.setVodPlayUrl(vodPlayUrl.toString());

        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (StringUtils.isEmpty(key)) return Result.error("搜索关键词为空");
        
        // 修复：正确编码关键词，拼接搜索URL
        String encodedKey = URLEncoder.encode(key, "UTF-8");
        String searchUrl = siteUrl + "/87s" + encodedKey + "----------1---.html";
        
        String html = OkHttp.string(searchUrl);
        if (html.contains("Just a moment")) {
            Notify.show("在线之家资源需要人机验证");
        }
        
        Document document = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        
        // 修复：选择器匹配新网站结构
        for (Element li : document.select(".stui-vodlist > li")) {
            String id = li.select("a.stui-vodlist__thumb").attr("href");
            String name = li.select(".stui-vodlist__detail > h4.title > a").text();
            String pic = li.select("a.stui-vodlist__thumb").attr("data-original");
            
            if (StringUtils.isEmpty(pic)) {
                pic = li.select("a.stui-vodlist__thumb > img").attr("src");
            }
            
            String category = li.select("a.stui-vodlist__thumb > span.pic-text1").text();
            String update = li.select("a.stui-vodlist__thumb > span.pic-text").text();
            String remark = StringUtils.isEmpty(category) ? update : (category + " " + update);

            if (StringUtils.isNotEmpty(id) && StringUtils.isNotEmpty(name)) {
                list.add(new Vod(id, name, pic, remark));
            }
        }

        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (StringUtils.isEmpty(id)) return Result.error("播放ID为空");
        
        // 1. 爬取播放页，提取player_aaaa参数
        String playPageUrl = siteUrl + id;
        String playPageHtml = OkHttp.string(playPageUrl, getHeader());
        
        Matcher playerMatcher = Pattern.compile("var player_aaaa=(\\{.*?\\});").matcher(playPageHtml);
        if (!playerMatcher.find()) {
            Notify.show("解析失败：未找到播放配置");
            return Result.error("未找到播放配置");
        }
        
        JSONObject playerJson = new JSONObject(playerMatcher.group(1));
        String encryptUrl = playerJson.optString("url", "");
        String type = playerJson.optString("from", "");
        String playData = playerJson.optString("play_data", "");
        String next = playPageUrl;

        if (StringUtils.isEmpty(encryptUrl) || StringUtils.isEmpty(type) || StringUtils.isEmpty(playData)) {
            Notify.show("解析失败：播放核心参数缺失");
            return Result.error("播放核心参数缺失");
        }

        // 2. 访问CDN链接，提取config参数
        String cdnDomain = "https://cdn-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp13.87kkt.com";
        String cdnPlayUrl = String.format(
            "%s/index.php?url=%s&type=%s&next=%s&data=%s",
            cdnDomain,
            encryptUrl,
            type,
            URLEncoder.encode(next, "UTF-8"),
            playData
        );

        Map<String, String> cdnHeader = getVideoHeader();
        cdnHeader.put("Referer", siteUrl);
        String cdnHtml = OkHttp.string(cdnPlayUrl, cdnHeader);

        Matcher configMatcher = Pattern.compile("var config = (\\{.*?\\});").matcher(cdnHtml);
        if (!configMatcher.find()) {
            Notify.show("解析失败：未找到CDN播放配置");
            return Result.error("未找到CDN播放配置");
        }

        JSONObject configJson = new JSONObject(configMatcher.group(1));
        String postUrlParam = configJson.optString("url", "");
        String time = configJson.optString("time", "");
        String vkey = configJson.optString("vkey", "");
        String key = configJson.optString("key", "");

        if (StringUtils.isEmpty(postUrlParam) || StringUtils.isEmpty(time) || StringUtils.isEmpty(vkey)) {
            Notify.show("解析失败：POST请求参数缺失");
            return Result.error("POST请求参数缺失");
        }

        // 3. 构造POST请求，获取真实播放地址（核心修复：处理OkResult）
        String postApi = cdnDomain + "/admin/mizhi_json.php";
        Map<String, String> postHeader = new HashMap<>();
        postHeader.put("x-requested-with", "XMLHttpRequest");
        postHeader.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
        postHeader.put("Accept", "application/json, text/javascript, */*; q=0.01");
        postHeader.put("sec-ch-ua", "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"");
        postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        postHeader.put("sec-ch-ua-mobile", "?0");
        postHeader.put("Origin", cdnDomain);
        postHeader.put("Referer", cdnPlayUrl);

        // 构造请求体
        String requestBody = String.format(
            "url=%s&time=%s&key=%s&vkey=%s",
            URLEncoder.encode(postUrlParam, "UTF-8"),
            URLEncoder.encode(time, "UTF-8"),
            URLEncoder.encode(key, "UTF-8"),
            URLEncoder.encode(vkey, "UTF-8")
        );

        // ========== 关键修复：处理OkResult类型 ==========
        OkResult result = OkHttp.post(postApi, requestBody, postHeader);
        // 检查请求是否成功（状态码200）
        if (!result.isSuccess()) {
            Notify.show("解析失败：POST请求失败（状态码：" + result.code() + "）");
            return Result.error("POST请求失败，状态码：" + result.code());
        }
        // 提取响应体字符串（自动关闭流）
        String postResponse = result.body().string();
        // 容错：响应体为空
        if (StringUtils.isEmpty(postResponse)) {
            Notify.show("解析失败：POST响应体为空");
            return Result.error("POST响应体为空");
        }
        // ==============================================

        // 4. 解析响应，提取真实播放地址
        JSONObject responseJson = new JSONObject(postResponse);
        String realPlayUrl = responseJson.optString("json_url", "");

        if (StringUtils.isEmpty(realPlayUrl)) {
            Notify.show("解析失败：未获取到真实播放地址");
            return Result.error("未获取到真实播放地址");
        }

        // 修复：移除Result.referer，将Referer加到header中
        Map<String, String> playHeader = getVideoHeader();
        playHeader.put("Referer", cdnDomain);

        return Result.get()
            .url(realPlayUrl)
            .header(playHeader)
            .string();
    }
}
