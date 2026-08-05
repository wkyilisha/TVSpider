package com.github.catvod.net;

import org.json.JSONObject;

public class RpcClient {

    /**
     * 发送密文到远端 Frida/Node 解密服务
     */
    public static String callRemoteDecrypt(String rpcServerUrl, String encryptedData) {
        try {
            JSONObject jsonParam = new JSONObject();
            jsonParam.put("data", encryptedData);
            
            // 调用项目原生的 OkHttp.post()，并通过 .getBody() 获取响应文本
            OkResult result = OkHttp.post(rpcServerUrl, jsonParam.toString(), null);
            String response = (result != null) ? result.getBody() : "";
            
            JSONObject resJson = new JSONObject(response);
            return resJson.optString("result", "");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}