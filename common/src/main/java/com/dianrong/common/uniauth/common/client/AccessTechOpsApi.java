package com.dianrong.common.uniauth.common.client;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.Cookie;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dianrong.common.uniauth.common.exp.LoginFailedException;
import com.dianrong.common.uniauth.common.exp.NetworkException;
import com.dianrong.common.uniauth.common.exp.NotLoginException;
import com.dianrong.common.uniauth.common.exp.OperationForbiddenException;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AccessTechOpsApi {
    @Autowired
    private ZooKeeperConfig zooKeeperConfig;
    private volatile HttpClient httpClient;

    public synchronized String getSessionId(String account, String password) {
        if (account == null || password == null || "".equals(account.trim()) || "".equals(password.trim())) {
            throw new IllegalArgumentException("Account or password is empty!");
        }
        String techOpsServerUrl = zooKeeperConfig.getTechOpsServerUrl();// "https://techops.dianrong.com/";//
                                                                        // zooKeeperConfig.getTechOpsServerUrl();
                                                                        // //"http://localhost:8090/techops/";
        String casServerUrl = zooKeeperConfig.getCasServerUrl();// "https://passport.dianrong.com";//
                                                                // zooKeeperConfig.getCasServerUrl();
                                                                // //"http://localhost:9080/cas";

        String serviceString = "service=" + techOpsServerUrl + "/login/cas";
        String casRestBaseUrl = casServerUrl + "/v1/tickets";

        HttpContent tgtRequestHc = new HttpContent();
        String requestBody = "username=" + URLEncoder.encode(account) + "&password=" + URLEncoder.encode(password) + "&" + serviceString;
        tgtRequestHc.setBody(requestBody);
        HttpContent tgtResponseHc = requestServer(casRestBaseUrl, "POST", tgtRequestHc, "application/x-www-form-urlencoded");
        log.info("get tgt:" + tgtResponseHc.toString());
        String tgtRestUrl = tgtResponseHc.getHeaders().get("Location");
        String tgt = tgtRestUrl.substring(tgtRestUrl.lastIndexOf("/"));

        HttpContent stRequestHc = new HttpContent();
        stRequestHc.setBody(serviceString);
        HttpContent stResponseHc = requestServer(casRestBaseUrl + tgt, "POST", stRequestHc, "application/x-www-form-urlencoded");
        log.info("get st" + stResponseHc.toString());
        String st = stResponseHc.getBody().trim();

        // remove cookie
        httpClient.getState().clearCookies();
        HttpContent sessionResponsetHc = requestServer(techOpsServerUrl + "/login/cas?ticket=" + st, "GET", null, null);
        log.info("st validation:" + sessionResponsetHc.toString());
        logHttpClientInfo("after ticket validation");

        String techopsCookie = sessionResponsetHc.getHeaders().get("Set-Cookie");
        if (techopsCookie != null && techopsCookie.split(";").length > 0 && techopsCookie.split(";")[0].split("=").length > 1) {
            return techopsCookie.split(";")[0].split("=")[1];
        }

        throw new LoginFailedException("Unknow login exception, please retry login");
    }

    public String accessApi(String sessionId, String apiPath, String method, String postBody) {
        logHttpClientInfo(apiPath + "||" + postBody);
        HttpContent apiRequestHc = new HttpContent();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cookie", "JSESSIONID=" + sessionId);
        apiRequestHc.setHeaders(headers);
        apiRequestHc.setBody(postBody);

        String techOpsServerUrl = zooKeeperConfig.getTechOpsServerUrl();// "https://techops.dianrong.com";//
                                                                        // zooKeeperConfig.getTechOpsServerUrl();
                                                                        // //"http://localhost:8090/techops/";

        if ("GET".equals(method)) {
            HttpContent apiResponseHc = requestServer(techOpsServerUrl + apiPath, method, apiRequestHc, null);
            return apiResponseHc.getBody();
        } else if ("POST".equals(method)) {
            HttpContent apiResponseHc = requestServer(techOpsServerUrl + apiPath, method, apiRequestHc, "application/json");
            return apiResponseHc.getBody();
        }
        throw new IllegalArgumentException("Only GET or POST supported!");
    }

    private HttpContent requestServer(String url, String method, HttpContent requestHttpContent, String contentType) {
        initHttpClient();
        HttpMethod httpMethod = null;
        if ("GET".equals(method)) {
            httpMethod = new GetMethod(url);
        } else if ("POST".equals(method)) {
            httpMethod = new PostMethod(url);
            httpMethod.addRequestHeader("Content-Type", contentType);
        }
        httpMethod.setFollowRedirects(false);
        if (requestHttpContent != null) {
            Map<String, String> requestHeaderMap = requestHttpContent.getHeaders();
            String requestBody = requestHttpContent.getBody();

            if (requestHeaderMap != null) {
                Iterator<Entry<String, String>> iterator = requestHeaderMap.entrySet().iterator();
                while (iterator.hasNext()) {
                    Entry<String, String> entry = iterator.next();
                    httpMethod.addRequestHeader(entry.getKey(), entry.getValue());
                }
            }

            if (requestBody != null && !"".equals(requestBody.trim()) && "POST".equals(method)) {
                ((PostMethod) httpMethod).setRequestBody(requestBody);
            }
        }

        int statusCode = 0;
        String location = null;
        String responseBody = null;
        HttpContent responseHttpContent = new HttpContent();

        try {
            httpClient.executeMethod(httpMethod);
            statusCode = httpMethod.getStatusCode();
            responseBody = httpMethod.getResponseBodyAsString();

            Header[] responseHeaders = httpMethod.getResponseHeaders();
            Map<String, String> responseHeaderMap = new HashMap<String, String>();
            if (responseHeaders != null) {
                for (Header responseHeader : responseHeaders) {
                    responseHeaderMap.put(responseHeader.getName(), responseHeader.getValue());
                }
            }
            location = responseHeaderMap.get("Location");
            location = location == null ? "" : location;

            responseHttpContent.setHeaders(responseHeaderMap);
            responseHttpContent.setBody(responseBody);
            responseHttpContent.setStatusCode(statusCode);

        } catch (Exception e) {
            throw new NetworkException("Maybe network exception?", e);
        } finally {
            httpMethod.releaseConnection();
        }

        if (statusCode == HttpStatus.SC_MOVED_TEMPORARILY && (location.indexOf("cas") != -1 || location.indexOf("passport") != -1) && url.indexOf("cas") == -1
                && url.indexOf("passport") == -1) {
            throw new NotLoginException("No valid jsessionid, maybe timeout?");
        } else if (statusCode == HttpStatus.SC_BAD_REQUEST && url.endsWith("/v1/tickets")) {
            throw new LoginFailedException("Wrong account/password!");
        } else if (statusCode == HttpStatus.SC_FORBIDDEN && url.indexOf("techops") != -1) {
            throw new OperationForbiddenException("Operation forbidden, maybe do not have sufficient privileges to perform this operation.");
        }

        return responseHttpContent;
    }

    private void initHttpClient() {
        if (httpClient == null) {
            synchronized (AccessTechOpsApi.class) {
                if (httpClient == null) {
                    MultiThreadedHttpConnectionManager manager = new MultiThreadedHttpConnectionManager();
                    HttpConnectionManagerParams params = new HttpConnectionManagerParams();
                    params.setDefaultMaxConnectionsPerHost(10);
                    params.setMaxTotalConnections(50);
                    params.setConnectionTimeout(10 * 1000);
                    params.setSoTimeout(60 * 1000);
                    manager.setParams(params);
                    httpClient = new HttpClient(manager);
                    // httpClient.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
                }
            }
        }
    }

    /**
     * . 记录httpclientinfo的信息
     */
    private void logHttpClientInfo(String methodName) {
        Cookie[] cookies = httpClient.getState().getCookies();
        StringBuilder sb = new StringBuilder();
        sb.append("httpclient cookies:[method]-" + methodName + ",");
        for (Cookie c : cookies) {
            sb.append(c.getName() + ":" + c.getValue() + ",");
        }
        log.info(sb.toString());
    }

    public class HttpContent {
        private Map<String, String> headers;
        private String body;
        private int statusCode;

        public int getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String toString() {
            return "\r\nstatusCode:\r\n " + statusCode + "\r\nheaders: \r\n" + headers + "\r\nbody:\r\n" + body;
        }
    }
}
