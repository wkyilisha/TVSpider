package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

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

public class Czzyv extends Spider {

    // 默认备用域名
    private static String HOST = "https://www.4kcz.com";
    private static final String NAV_URL = "https://www.czzy.site/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("国产剧", "gcj");
        CATEGORY_MAP.put("美剧", "meijutt");
        CATEGORY_MAP.put("韩剧", "hanjutv");
        CATEGORY_MAP.put("番剧", "fanju");
        CATEGORY_MAP.put("剧场版", "dongmanjuchangban");
        CATEGORY_MAP.put("最新电影", "zuixindianying");
        CATEGORY_MAP.put("豆瓣电影Top250", "dbtop250");
    }

    private void logger(String msg) {
        try {
            Proxy.log("[Czzyv] " + msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * 请求真实站点时，Referer 固定使用导航页，降低触发验证的概率
     */
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", NAV_URL);          // 关键：始终带导航页 Referer
        headers.put("Origin", HOST);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String get(String url) {
        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            logger("请求失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 从导航页提取最新可用域名
     */
    private void resolveHost() {
        try {
            logger("正在访问导航页获取最新域名: " + NAV_URL);

            Map<String, String> navHeaders = new HashMap<>();
            navHeaders.put("User-Agent", UA);
            navHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            navHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");

            String html = OkHttp.string(NAV_URL, navHeaders);
            if (TextUtils.isEmpty(html)) {
                logger("导航页获取失败，使用默认域名: " + HOST);
                return;
            }

            Document doc = Jsoup.parse(html);
            Elements links = doc.select("a");

            // 1. 优先找「点击这里手动跳转」
            for (Element a : links) {
                String text = a.text().trim();
                if (text.contains("点击这里手动跳转") || text.contains("手动跳转")) {
                    String href = a.attr("href").trim();
                    if (!TextUtils.isEmpty(href)) {
                        if (href.startsWith("//")) href = "https:" + href;
                        if (!href.startsWith("http")) href = "https://" + href;
                        if (href.endsWith("/")) href = href.substring(0, href.length() - 1);
                        HOST = href;
                        logger("✅ 从导航页获取到最新域名: " + HOST);
                        return;
                    }
                }
            }

            // 2. 备用匹配
            for (Element a : links) {
                String href = a.attr("href").trim();
                if (href.contains("4kcz.com") || href.contains("czzy.top") || href.contains("cz4k.com")) {
                    if (href.startsWith("//")) href = "https:" + href;
                    if (!href.startsWith("http")) href = "https://" + href;
                    if (href.endsWith("/")) href = href.substring(0, href.length() - 1);
                    HOST = href;
                    logger("✅ 备用匹配到域名: " + HOST);
                    return;
                }
            }

            logger("未从导航页解析到新域名，继续使用默认: " + HOST);
        } catch (Exception e) {
            logger("解析导航页异常: " + e.getMessage() + "，使用默认域名: " + HOST);
        }
    }

    private String extractMysvgValue(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Pattern p = Pattern.compile("const\\s+mysvg\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractVideoUrlFromPlayPage(String playUrl) {
        try {
            String html = get(playUrl);
            if (TextUtils.isEmpty(html)) return null;

            Pattern iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher iframeMatcher = iframePattern.matcher(html);

            if (iframeMatcher.find()) {
                String iframeUrl = iframeMatcher.group(1);
                logger("iframe URL: " + iframeUrl);
                if (iframeUrl.startsWith("/")) iframeUrl = HOST + iframeUrl;

                String iframeHtml = get(iframeUrl);
                if (TextUtils.isEmpty(iframeHtml)) {
                    logger("iframe 页面获取失败");
                    return null;
                }

                String videoUrl = extractMysvgValue(iframeHtml);
                if (videoUrl != null) {
                    logger("提取到 mysvg 真实视频地址: " + videoUrl);
                    return videoUrl;
                }
            }
            return null;
        } catch (Exception e) {
            logger("提取视频地址失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化 Spider 插件...");
        resolveHost();
        logger("当前使用域名: " + HOST);
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getValue(), entry.getKey()));
        }
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String url = (page == 1) ? HOST + "/" + tid : HOST + "/" + tid + "/page/" + page;

            logger("分类请求: " + tid + " 页码: " + page + " → " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".bt_img ul li");

            List<Vod> list = new ArrayList<>();
            for (Element item : items) {
                Element link = item.selectFirst("a");
                if (link == null) continue;

                String href = link.attr("href");
                if (TextUtils.isEmpty(href)) continue;
                String vodId = href.replace(HOST, "");

                Element img = link.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }

                Element jidi = link.selectFirst(".jidi span");
                String remarks = jidi != null ? jidi.text() : "";

                Element titleElem = item.selectFirst("h3.dytit a");
                String name = titleElem != null ? titleElem.text() : "";

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("分类解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;

            logger("详情页请求: " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);

            String name = "";
            Element titleElem = doc.selectFirst(".moviedteail_tt h1");
            if (titleElem != null) name = titleElem.text();

            String pic = "";
            Element imgElem = doc.selectFirst(".dyimg img");
            if (imgElem != null) pic = imgElem.attr("src");

            String area = "";
            Element areaElem = doc.selectFirst(".moviedteail_list li:contains(地区：)");
            if (areaElem != null) {
                Elements areaLinks = areaElem.select("a");
                List<String> areas = new ArrayList<>();
                for (Element a : areaLinks) areas.add(a.text());
                area = TextUtils.join(", ", areas);
            }

            String year = "";
            Element yearElem = doc.selectFirst(".moviedteail_list li:contains(年份：) a");
            if (yearElem != null) year = yearElem.text();

            String director = "";
            Element dirElem = doc.selectFirst(".moviedteail_list li:contains(导演：) span");
            if (dirElem != null) director = dirElem.text();

            String actor = "";
            Element actorElem = doc.selectFirst(".moviedteail_list li:contains(主演：)");
            if (actorElem != null) {
                Elements actorSpans = actorElem.select("span");
                List<String> actors = new ArrayList<>();
                for (Element span : actorSpans) actors.add(span.text());
                actor = TextUtils.join(", ", actors);
            }

            String content = "";
            Element contentElem = doc.selectFirst(".yp_context");
            if (contentElem != null) content = contentElem.text().trim();

            List<String> playUrls = new ArrayList<>();
            Elements playLinks = doc.select(".paly_list_btn a");
            for (Element link : playLinks) {
                playUrls.add(link.text() + "$" + link.attr("href"));
            }

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodYear(year);
            vod.setVodArea(area);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodRemarks(year.isEmpty() ? "" : year + "年");

            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("橙子影视");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }
            return Result.string(vod);
        } catch (Exception e) {
            logger("详情解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id;
            logger("播放页解析: " + playUrl);

            String videoUrl = extractVideoUrlFromPlayPage(playUrl);

            if (TextUtils.isEmpty(videoUrl)) {
                logger("未能提取到视频直链，交由播放器嗅探");
                return Result.get().parse(1).url(playUrl).string();
            }

            logger("解析成功，真实视频地址: " + videoUrl);

            // 播放直链不带导航页 Referer，只保留 UA + Origin
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Origin", HOST);

            return Result.get().url(videoUrl).header(headers).string();
        } catch (Exception e) {
            logger("播放处理异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String url = HOST + "/boss1O1?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求: " + url);

            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                logger("搜索页面获取失败");
                return Result.string(new ArrayList<>());
            }

            logger("搜索页面长度: " + html.length());

            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".search_list ul li");
            if (items.isEmpty()) items = doc.select(".bt_img ul li");
            if (items.isEmpty()) items = doc.select("ul li");

            logger("找到候选条目数: " + items.size());

            List<Vod> resultList = new ArrayList<>();
            String normalizedKey = key.trim().toLowerCase();

            for (Element item : items) {
                Element titleLink = item.selectFirst("h3.dytit a");
                if (titleLink == null) titleLink = item.selectFirst("h3 a");
                if (titleLink == null) titleLink = item.selectFirst("a");
                if (titleLink == null) continue;

                String title = titleLink.text().trim();
                if (TextUtils.isEmpty(title)) continue;

                String normTitle = title.toLowerCase();
                if (!normTitle.contains(normalizedKey) && !normalizedKey.contains(normTitle)) {
                    continue;
                }

                logger("匹配到: " + title);

                String detailUrl = titleLink.attr("href");
                if (TextUtils.isEmpty(detailUrl)) continue;

                Element img = item.selectFirst("img");
                String picUrl = "";
                if (img != null) {
                    picUrl = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }

                Vod vod = new Vod();
                vod.setVodId(detailUrl);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                vod.setVodRemarks("");
                resultList.add(vod);
            }

            logger("搜索完成，返回匹配结果数: " + resultList.size());
            return Result.string(resultList);
        } catch (Exception e) {
            logger("搜索出现异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }
}
