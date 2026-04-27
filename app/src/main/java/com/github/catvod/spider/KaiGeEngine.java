package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64; 
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;

/**
 * 凱哥標準規則引擎 2.0 (空格自由版)
 * 已修復：重複方法定義、支持符號前後任意空格、保護提取規則內部空格
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        // 🚀 1. 指令拆分 (;; 分隔)
        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic = segments[0].trim();

        // --- 🚀 關鍵修改點 A：符號轉換 (支持 * 和 +) ---
        // 將你習慣的 * 和 + 在進入處理前，自動轉為引擎認識的 > 
        if (coreLogic.contains("*") || coreLogic.contains("+")) {
            coreLogic = coreLogic.replace("*", " > ").replace("+", " > ");
        }

        // 解析後綴指令（如 [full], [包含:], [排除:] 等）
        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]")) {
                result.shouldFull = true;
            }
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) {
                result.includeKey = tag.substring(4, tag.length() - 1);
            }
            if (tag.startsWith("[排除:")) {
                result.excludeKey = tag.substring(4, tag.length() - 1);
            }
        }

        // 2. 處理核心邏輯 (現在 coreLogic 裡的 * 和 + 已經變成 > 了)
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

        // --- 🚀 關鍵修改點 B：自動解碼 (支持 [base64] 和 [url_decode]) ---
        if (!isEmpty(finalValue)) {
            // Base64 解碼處理
            if (rule.contains("[base64]")) {
                try {
                    finalValue = new String(android.util.Base64.decode(finalValue, android.util.Base64.DEFAULT));
                } catch (Exception e) {
                    // 解碼失敗則保留原樣
                }
            }
            // URL 解碼處理
            if (rule.contains("[url_decode]")) {
                try {
                    finalValue = java.net.URLDecoder.decode(finalValue, "UTF-8");
                } catch (Exception e) {
                    // 解碼失敗則保留原樣
                }
            }
        }

        // 3. 過濾與補全
        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) {
            finalValue = "";
        }
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) {
            finalValue = "";
        }
        if (result.shouldFull && !isEmpty(finalValue)) {
            finalValue = autoFullUrl(finalValue, host);
        }

        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
        if (isEmpty(step)) return content;
        
        if (step.equalsIgnoreCase("[base64]")) {
            try { return new String(Base64.decode(content, Base64.DEFAULT)); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("[url_decode]")) {
            try { return java.net.URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.startsWith("[reg:")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }
        
        // 🚀 3. 處理拼接：支持 + 號前後任意空格
        if (step.contains("+")) {
            return handleCombination(content, step, host);
        }

        return executeSingleRule(content, step);
    }

    private static String executeSingleRule(String html, String rule) {
        if (rule.contains("@")) {
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
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html; 
    }

    // 🚀 核心修改：只保留一個強大的 handleCombination，支持 + 前後任意空格
    private static String handleCombination(String html, String logic, String host) {
        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();
        
        for (String p : parts) {
            String item = p.trim(); 
            
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                sb.append(item.substring(1, item.length() - 1));
            } 
            else if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } 
            else {
                sb.append(item);
            }
        }
        return sb.toString();
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Pattern pattern = Pattern.compile(fullRegex, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            return matcher.find() ? matcher.group(1).trim() : "";
        } catch (Exception e) { return ""; }
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
