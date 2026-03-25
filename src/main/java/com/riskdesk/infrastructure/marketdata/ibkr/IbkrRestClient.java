package com.riskdesk.infrastructure.marketdata.ibkr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared HTTP client for IBKR Client Portal Gateway.
 * Disables SSL verification by default (self-signed cert on localhost).
 */
public class IbkrRestClient {

    private static final Logger log = LoggerFactory.getLogger(IbkrRestClient.class);

    private final RestTemplate restTemplate;
    private final String       baseUrl;

    public IbkrRestClient(IbkrProperties props) {
        this.baseUrl      = props.getGatewayUrl() + "/v1/api";
        this.restTemplate = buildRestTemplate(props);
        log.info("IBKR Client Portal Gateway configured at {}", baseUrl);
    }

    public RestTemplate restTemplate() { return restTemplate; }
    public String       baseUrl()      { return baseUrl; }

    private RestTemplate buildRestTemplate(IbkrProperties props) {
        if (!props.isSslVerify()) {
            try {
                // Trust all certs — acceptable for localhost IBKR Gateway only
                TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());

                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
                log.debug("IBKR: SSL verification disabled for localhost gateway");
            } catch (Exception e) {
                throw new RuntimeException("IBKR SSL setup failed", e);
            }
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection conn, String method) throws java.io.IOException {
                super.prepareConnection(conn, method);
                // Forward cookies to maintain IBKR session
                conn.setUseCaches(false);
            }
        };
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
