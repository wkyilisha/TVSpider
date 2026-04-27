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
        if (rule.contains("@")) {
            // ... 原有的属性提取逻辑保持不动 ...
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim(); 
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1).trim();
            return ""; 
        }

        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            
            // 🚀 核心修改：让它能识别你的 * 号
            // 只要 start 包含 *，就走通配符匹配，否则走原本的 simpleCut
            if (start.contains("*")) {
                return cutWithWildcard(html, start, end);
            }
            return simpleCut(html, start, end);
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
            // 🚀 1. 按照你的想法：分割 * 号左右两部分
            String[] parts = startRule.split("\\*");
            String head = parts[0]; // var config
            String tail = parts.length > 1 ? parts[1] : ""; // url\":\"

            // 🚀 2. 先找 head 的位置
            int headIdx = html.indexOf(head);
            if (headIdx == -1) return "";

            // 🚀 3. 从 head 之后的位置开始找 tail
            int tailStartIdx = html.indexOf(tail, headIdx + head.length());
            if (tailStartIdx == -1) return "";

            // 🚀 4. 找到 tail 的末尾，也就是数据开始的地方
            int dataStartIdx = tailStartIdx + tail.length();

            // 🚀 5. 最后用 end (&& 后面的内容) 做切刀
            if (isEmpty(end)) return html.substring(dataStartIdx).trim();
            int endIdx = html.indexOf(end, dataStartIdx);
            
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
                if (e > -1) return html.substring(s, e).trim();
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
