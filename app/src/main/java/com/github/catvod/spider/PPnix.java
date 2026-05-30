package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.ResUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PPnix extends Spider {

    private static final String HOST = "https://www.ppnix.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static volatile String cfCookie = "";

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        h.put("Origin", HOST);
        if (!TextUtils.isEmpty(cfCookie)) h.put("Cookie", cfCookie);
        return h;
    }

    // =========================
    // init — 去掉弹窗，主动启动Proxy服务器
    // =========================
    @Override
    public void init(Context context, String extend) {
        Proxy.log("PPnix 插件加载成功");
    }

    // =========================
    // 分类
    // =========================
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
            String html = OkHttp.string(url, baseHeaders(HOST + "/"));

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();

            for (Element li : doc.select(".lists-content ul li")) {

                Element a = li.selectFirst("a.thumbnail");
                if (a == null) continue;

                String id = a.attr("href");
                if (!id.startsWith("/")) id = "/" + id;

                String name = "";
                Element title = li.selectFirst("h2 a");
                if (title != null) name = title.text();

                String pic = "";
                Element img = a.selectFirst("img");
                if (img != null) pic = img.attr("src");

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                vod.setVodPic(pic);

                list.add(vod);
            }

            return Result.string(list);

        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    // =========================
    // 详情
    // =========================
    @Override
    public String detailContent(List<String> ids) {

        try {

            String url = HOST + ids.get(0);
            String html = OkHttp.string(url, baseHeaders(HOST + "/"));

            Document doc = Jsoup.parse(html);

            Vod vod = new Vod();

            String infoid = "";
            List<String> playUrls = new ArrayList<>();

            for (Element script : doc.select("script")) {

                String data = script.data();

                if (data.contains("infoid") && data.contains("m3u8")) {

                    Matcher m = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(data);
                    if (m.find()) infoid = m.group(1);

                    Matcher ep = Pattern.compile("(\\d+)").matcher(data);
                    while (ep.find()) {
                        String e = ep.group(1);
                        playUrls.add("第" + e + "集$/info/m3u8/" + infoid + "/" + e + ".m3u8");
                    }
                    break;
                }
            }

            vod.setVodId(ids.get(0));
            vod.setVodName(doc.title());

            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("PPnix");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }

            return Result.string(vod);

        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    // =========================
    // 播放（走Proxy本地代理）
    // =========================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {

        try {

            String originalUrl = id.startsWith("http") ? id : HOST + id;

            // 交给Proxy的 ?do=m3u8&url= 进行域名随机替换后返回给播放器
            String proxyUrl = Proxy.getUrl() + "?do=m3u8&url=" + URLEncoder.encode(originalUrl, "UTF-8");

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");
            headers.put("Origin", HOST);

            return Result.get().url(proxyUrl).header(headers).string();

        } catch (Exception e) {
            logger("❌ 播放失败: " + e.getMessage());
            return Result.get().url(id).string();
        }
    }

    // =========================
    // 搜索
    // =========================
    @Override
    public String searchContent(String key, boolean quick) {
        return Result.string(new ArrayList<>());
    }
}
