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
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PPnix extends Spider {

    private static final String HOST = "https://www.ppnix.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", HOST + "/");
        return h;
    }

    @Override
    public void init(Context context, String extend) {
        // 直接在初始化时记录日志，不再弹出密码框
        try {
            Proxy.log("✅ PPnix 引擎启动成功 (已对接 10086 代理)");
        } catch (Exception ignored) {}
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> list = new ArrayList<>();
        list.add(new Class("movie", "电影"));
        list.add(new Class("tv", "电视剧"));
        return Result.string(list, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg) - 1;
            String url = String.format("%s/cn/%s/---%d-.html", HOST, tid, page);
            String html = OkHttp.string(url, baseHeaders());
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();

            for (Element li : doc.select(".lists-content ul li")) {
                Element a = li.selectFirst("a.thumbnail");
                if (a == null) continue;

                String id = a.attr("href");
                String name = "";
                Element title = li.selectFirst("h2 a");
                if (title != null) name = title.text();

                String pic = "";
                Element img = a.selectFirst("img");
                if (img != null) pic = img.attr("src");

                list.add(new Vod(id, name, pic));
            }
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = HOST + ids.get(0);
            String html = OkHttp.string(url, baseHeaders());
            Document doc = Jsoup.parse(html);

            Vod vod = new Vod();
            List<String> playUrls = new ArrayList<>();

            for (Element script : doc.select("script")) {
                String data = script.data();
                if (data.contains("infoid") && data.contains("m3u8")) {
                    // 提取 infoid
                    Matcher m = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(data);
                    String infoid = m.find() ? m.group(1) : "";
                    
                    // 提取集数
                    Matcher ep = Pattern.compile("(\\d+)").matcher(data);
                    while (ep.find()) {
                        String e = ep.group(1);
                        // 过滤掉 infoid 自身和过长的干扰数字
                        if (!e.equals(infoid) && e.length() < 4) {
                            // 拼接相对路径
                            playUrls.add("第" + e + "集$/info/m3u8/" + infoid + "/" + e + ".m3u8");
                        }
                    }
                    break;
                }
            }

            vod.setVodId(ids.get(0));
            vod.setVodName(doc.title());
            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("PPnix-Proxy");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }
            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // 补全原始播放地址
            String originalUrl = id.startsWith("http") ? id : HOST + id;

            // 构造传给 Proxy.java 的 URL
            // 对应 Proxy.java 中的 params.get("do").equals("m3u8") 逻辑
            String proxyUrl = Proxy.getUrl() + "?do=m3u8&url=" + URLEncoder.encode(originalUrl, "UTF-8");

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");

            Proxy.log("📡 代理播放: " + originalUrl);
            return Result.get().url(proxyUrl).header(headers).string();

        } catch (Exception e) {
            // 出错则尝试直连
            return Result.get().url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return Result.string(new ArrayList<>());
    }
}
