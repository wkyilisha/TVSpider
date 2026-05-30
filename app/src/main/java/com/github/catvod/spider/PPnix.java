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

import java.io.File;
import java.io.FileOutputStream;
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
        // 主动触发Proxy服务器启动，不弹任何窗口
        Proxy.log("PPnix 插件加载成功");
    }

    // =========================
    // m3u8本地化（核心）
    // =========================
    private String processM3u8(String m3u8Url, String referer) {
        try {
            String content = OkHttp.string(m3u8Url, baseHeaders(referer));
            if (TextUtils.isEmpty(content)) return null;

            Random rnd = new Random();
            int hostNum = rnd.nextInt(16) + 1; // 1~16，整个m3u8用同一个随机域名

            StringBuilder sb = new StringBuilder();
            String baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf("/") + 1);
            int replaceCount = 0;

            for (String raw : content.split("\n")) {
                String line = raw.trim();

                if (line.startsWith("#")) {
                    sb.append(raw).append("\n");
                    continue;
                }

                if (line.isEmpty()) {
                    sb.append("\n");
                    continue;
                }

                // 相对路径转绝对路径
                if (!line.startsWith("http")) {
                    line = baseUrl + line;
                }

                // ipfs域名随机替换
                if (line.contains("ipfs.ppnix.com")) {
                    line = line.replace("ipfs.ppnix.com", hostNum + ".ppnix.com");
                    replaceCount++;
                }

                sb.append(line).append("\n");
            }

            logger("✅ m3u8处理完成，共替换 " + replaceCount + " 个TS域名 → " + hostNum + ".ppnix.com");

            // 写入本地缓存文件
            File file = new File(
                    Init.context().getCacheDir(),
                    "ppnix_" + System.currentTimeMillis() + ".m3u8"
            );
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();

            return "file://" + file.getAbsolutePath();

        } catch (Exception e) {
            logger("❌ m3u8处理失败: " + e.getMessage());
            return null;
        }
    }

    // =========================
    // 首页分类
    // =========================
    @Override
    public String homeContent(boolean filter) {
        List<Class> list = new ArrayList<>();
        list.add(new Class("movie", "电影"));
        list.add(new Class("tv", "电视剧"));
        return Result.string(list, new ArrayList<>());
    }

    // =========================
    // 分类列表
    // =========================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg) - 1;
            String url = String.format("%s/cn/%s/---%d-.html", HOST, tid, page);
            logger("📄 分类请求: " + url);

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
                if (img != null) {
                    pic = img.attr("src");
                    if (TextUtils.isEmpty(pic)) pic = img.attr("data-src");
                }

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                vod.setVodPic(pic);
                list.add(vod);
            }

            logger("📦 分类结果: " + list.size() + " 条");
            return Result.string(list);

        } catch (Exception e) {
            logger("❌ 分类失败: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // =========================
    // 详情页
    // =========================
    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = HOST + ids.get(0);
            logger("🎬 详情请求: " + url);

            String html = OkHttp.string(url, baseHeaders(HOST + "/"));
            Document doc = Jsoup.parse(html);

            Vod vod = new Vod();
            String infoid = "";
            List<String> playUrls = new ArrayList<>();

            for (Element script : doc.select("script")) {
                String data = script.data();

                if (data.contains("infoid") && data.contains("m3u8")) {
                    // 提取 infoid
                    Matcher m = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(data);
                    if (m.find()) infoid = m.group(1);

                    logger("🔑 infoid = " + infoid);

                    // 提取集数数组，格式通常为 eps=[1,2,3] 或 eps=["1","2","3"]
                    Matcher ep = Pattern.compile("eps\\s*=\\s*\\[([^\\]]+)\\]").matcher(data);
                    if (ep.find()) {
                        String[] parts = ep.group(1).split(",");
                        for (String part : parts) {
                            String e = part.trim().replaceAll("[\"'\\s]", "");
                            if (!e.isEmpty() && !e.equals(infoid)) {
                                // 拼接格式：显示名称$播放路径
                                // playerContent收到的id就是 /info/m3u8/{infoid}/{e}.m3u8
                                playUrls.add("第" + e + "集$/info/m3u8/" + infoid + "/" + e + ".m3u8");
                            }
                        }
                    } else {
                        // 兜底：如果找不到eps数组，尝试单集
                        logger("⚠️ 未找到eps数组，尝试单集兜底");
                        if (!infoid.isEmpty()) {
                            playUrls.add("播放$/info/m3u8/" + infoid + "/1.m3u8");
                        }
                    }
                    break;
                }
            }

            vod.setVodId(ids.get(0));
            vod.setVodName(doc.title());

            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("PPnix");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
                logger("✅ 共解析 " + playUrls.size() + " 集");
            } else {
                logger("⚠️ 未解析到任何播放链接");
            }

            return Result.string(vod);

        } catch (Exception e) {
            logger("❌ 详情失败: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // =========================
    // 播放（核心）
    // =========================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // id 格式：/info/m3u8/12345/1.m3u8
            // 拼成完整URL：https://www.ppnix.com/info/m3u8/12345/1.m3u8
            String originalUrl = id.startsWith("http") ? id : HOST + id;
            String referer = HOST + "/";

            logger("▶️ 播放请求: " + originalUrl);

            // 下载m3u8并本地化（域名随机替换）
            String finalUrl = processM3u8(originalUrl, referer);

            if (TextUtils.isEmpty(finalUrl)) {
                logger("⚠️ m3u8本地化失败，降级直接播放");
                finalUrl = originalUrl;
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", referer);

            return Result.get().url(finalUrl).header(headers).string();

        } catch (Exception e) {
            logger("❌ 播放失败: " + e.getMessage());
            return Result.get().url(id).string();
        }
    }

    // =========================
    // 搜索（暂不支持）
    // =========================
    @Override
    public String searchContent(String key, boolean quick) {
        return Result.string(new ArrayList<>());
    }
}
