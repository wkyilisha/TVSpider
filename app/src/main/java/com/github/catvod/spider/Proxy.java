package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
        if (!isServerRunning) startLegacyServer();
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String req = in.readLine();
                        if (req == null) continue;

                        try (OutputStream out = client.getOutputStream()) {
                            // 🚀 分支 1：清空指令
                            if (req.contains("/?clean")) {
                                sb.setLength(0);
                                sb.append("<div style='color:red;'>--- 日誌已手動清空 ---</div>");
                                out.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes());
                            } 
                            // 🚀 分支 2：專門給 JS 調用的純數據接口
                            else if (req.contains("/get_logs")) {
                                String data = sb.toString();
                                String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + data;
                                out.write(resp.getBytes("UTF-8"));
                            } 
                            // 🚀 分支 3：主面板界面
                            else {
                                String html = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                                        "<html><head><meta charset='utf-8'><style>" +
                                        "body{background:#fff;color:#000;font-family:monospace;font-size:12px;margin:0;padding:10px;}" +
                                        ".header{position:sticky;top:0;background:#fff;padding:5px;border-bottom:1px solid #000;display:flex;justify-content:space-between;z-index:9;}" +
                                        ".time{color:#888;margin-right:5px;}.line{border-bottom:1px solid #eee;padding:2px 0;}" +
                                        "button{background:#000;color:#fff;border:none;padding:4px 8px;border-radius:3px;}" +
                                        "</style></head><body>" +
                                        "<div class='header'><b>📟 凱哥監聽</b><button onclick='clr()'>🧹 清空</button></div>" +
                                        "<div id='logs'>正在對接矩陣數據...</div>" +
                                        "<script>" +
                                        "function clr(){fetch('/?clean').then(()=>location.reload());}" +
                                        "let last = '';" +
                                        "setInterval(() => {" +
                                        "  fetch('/get_logs').then(r=>r.text()).then(data=>{" + // 🚀 關鍵：只拿純日誌數據
                                        "    if(data !== last) {" +
                                        "      document.getElementById('logs').innerHTML = data;" +
                                        "      last = data;" +
                                        "      window.scrollTo(0, document.body.scrollHeight);" +
                                        "    }" +
                                        "  });" +
                                        "}, 1000);" +
                                        "</script></body></html>";
                                out.write(html.getBytes("UTF-8"));
                            }
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    public Object[] proxy(Map<String, String> params) { return null; }
}
