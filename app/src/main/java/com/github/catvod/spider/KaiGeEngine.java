package com.github.catvod.spider;

import android.util.Base64;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凱哥標準規則引擎 2.0 (最強適配版)
 * 已修復：* 通配符定位、+ 域名邊切邊拼、[base64][url_decode] 雙解碼順序
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    // --- 修改從這裡開始 ---

    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        // 🚀 1. 指令拆分 (;; 分隔)
        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[排除:")) result.excludeKey = tag.substring(4, tag.length() - 1);
        }

        // 🚀 2. 處理核心邏輯 (支持 > 鏈接跳轉)
        String finalValue = "";
        if (coreLogic.contains(">")) {
            String[] steps = coreLogic.split("\\s*>\\s*");
            finalValue = html;
            for (String step : steps) {
                finalValue = processStep(finalValue, step.trim(), host);
            }
        } else {
            finalValue = processStep(html, coreLogic, host);
        }

        // 🚀 3. 強制雙解碼 (解決你說的解密不對問題：只要規則裡有，最後統一按順序解)
        // 🚀 核心修改：适配你的双解码逻辑
        if (!isEmpty(finalValue)) {
            // 只要规则里包含 [base64]，不管写在哪，最后统一解一次
            if (rule.contains("[base64]")) {
                try { finalValue = new String(Base64.decode(finalValue, Base64.DEFAULT)); } catch (Exception e) {}
            }
            // 只要规则里包含 [url_decode]，最后再还原斜杠
            if (rule.contains("[url_decode]")) {
                try { finalValue = java.net.URLDecoder.decode(finalValue, "UTF-8"); } catch (Exception e) {}
            }
        }

        // 🚀 核心修改：适配包含和排除
        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) finalValue = "";

        // 补全域名逻辑
        if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);


        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
        if (isEmpty(step)) return content;

        // 跳過解碼指令（因為已經在 doExtract 結尾統一處理了）
        if (step.equalsIgnoreCase("[base64]") || step.equalsIgnoreCase("[url_decode]")) {
            return content;
        }

        if (step.startsWith("[reg:")) {
            Matcher m = Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }

        // 🚀 核心修改：處理 + 拼接時，支持內部使用 && 提取變量
        if (step.contains("+")) {
            return handleCombination(content, step, host);
        }

        return executeSingleRule(content, step);
    }

private static String executeSingleRule(String html, String rule) {
        // ... 前面处理 @ 属性提取的逻辑保持不动 ...

        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String currentContent = html;

            // 🚀 核心修改：多级切刀逻辑
            // 假设规则是 A && B && C
            // 我们循环处理，前几段用来“缩小范围”，最后一段用来“截取结果”
            for (int i = 0; i < parts.length - 1; i++) {
                String start = parts[i].trim().replace("\\\"", "\"");
                String nextPart = parts[i + 1].trim().replace("\\\"", "\"");

                if (i < parts.length - 2) {
                    // 还没到最后一段：只是为了把头切掉，缩小范围
                    // 比如 var config && url
                    // 先找到 var config，把前面的都扔了
                    int s = currentContent.indexOf(start);
                    if (s > -1) {
                        currentContent = currentContent.substring(s + start.length());
                    } else {
                        return ""; // 任何一级定位不到就断开
                    }
} else {
                    // 🚀 就在這裡修改：執行最後一段切割
                    // 如果最後一段規則（例如 url*":"）包含 *，就走通配符，否則走普通切割
                    if (start.contains("*")) {
                        currentContent = cutWithWildcard(currentContent, start, nextPart);
                    } else {
                        currentContent = simpleCut(currentContent, start, nextPart);
                    }
                }
            }
            return currentContent;
        }
        return html;
    }


    private static String handleCombination(String html, String logic, String host) {
        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();

        for (String p : parts) {
            String item = p.trim();
            // 🚀 核心修改：如果是提取指令（含 @ 或 &&），先去摳內容再拼
            if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } else {
                // 否則當作純文字（自動去掉規則裡的引號）
                sb.append(item.replace("\"", "").replace("'", ""));
            }
        }
        return sb.toString();
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            // 🚀 核心：先清理規則裡的轉義斜槓，保證跟源碼字符像素級對齊
            String cleanStart = startRule.replace("\\\"", "\"");
            String cleanEnd = (end != null) ? end.replace("\\\"", "\"") : "";

            // 🚀 1. 按照你的想法：分割 * 號左右兩部分
            String[] parts = cleanStart.split("\\*");
            String head = parts[0]; 
            String tail = parts.length > 1 ? parts[1] : ""; 

            // 🚀 2. 定位頭 (例如: var config)
            int headIdx = html.indexOf(head);
            if (headIdx == -1) return "";

            // 🚀 3. 從頭後面找尾 (例如: url":")
            int tailStartIdx = html.indexOf(tail, headIdx + head.length());
            if (tailStartIdx == -1) return "";

            // 🚀 4. 計算數據起點
            int dataStartIdx = tailStartIdx + tail.length();

            // 🚀 5. 用 end 作為切刀 (例如: " 號)
            if (isEmpty(cleanEnd)) return html.substring(dataStartIdx).trim();
            int endIdx = html.indexOf(cleanEnd, dataStartIdx);
            
            if (endIdx > -1) {
                return html.substring(dataStartIdx, endIdx).trim();
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }



private static String simpleCut(String html, String start, String end) {
        try {
            int s = html.indexOf(start);
            if (s > -1) {
                s += start.length();
                if (isEmpty(end)) return html.substring(s).trim();
                int e = html.indexOf(end, s);
                if (e > -1) {
                    String res = html.substring(s, e).trim();
                    // 🚀 针对纯 JSON 或 JS 变量的特殊清理
                    // 去掉开头和结尾可能包裹的引号（单引号或双引号）
                    if ((res.startsWith("\"") && res.endsWith("\"")) || (res.startsWith("'") && res.endsWith("'"))) {
                        res = res.substring(1, res.length() - 1);
                    }
                    // 强制还原转义斜杠（JSON 里的 \/ 还原成 /）
                    return res.replace("\\/", "/"); 
                }
            }
        } catch (Exception e) { return ""; }
        return "";
    }

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) {
            if (host.endsWith("/")) return host + path.substring(1);
            return host + path;
        }
        return host + (host.endsWith("/") ? "" : "/") + path;
    }

    public static class ExtractionResult {
        public String value = "";
        public boolean shouldFull = false;
        public int index = 0;
        public String includeKey = "";
        public String excludeKey = "";
    }
}
