package com.github.catvod.net;

import org.json.JSONObject;

public class RpcClient {

    public static String callRemoteDecrypt(String rpcServerUrl, String encryptedData) {
        try {
            JSONObject jsonParam = new JSONObject();
            jsonParam.put("data", encryptedData);
            
            String response = OkHttp.post(rpcServerUrl, jsonParam.toString(), null);
            JSONObject resJson = new JSONObject(response);
            return resJson.optString("result", "");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}