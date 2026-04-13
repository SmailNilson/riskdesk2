package com.riskdesk.infrastructure.marketdata.ibkr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the active IBKR backend.
 *
 * IB_GATEWAY:
 *   1. Start TWS or IB Gateway
 *   2. Enable API connections in the IBKR client
 *   3. Point host/port below to the native socket API
 *
 * CLIENT_PORTAL:
 *   Legacy compatibility path for the REST/Web API gateway.
 */
@ConfigurationProperties(prefix = "riskdesk.ibkr")
public class IbkrProperties {

    private boolean enabled = false;
    private IbkrBackendMode mode = IbkrBackendMode.IB_GATEWAY;
    private String  gatewayUrl        = "https://localhost:5001";
    private String  nativeHost        = "127.0.0.1";
    private int     nativePort        = 4002;
    private int     nativeClientId    = 7;
    /** Separate clientId for the tick-by-tick EClientSocket connection (bypasses ApiController). */
    private int     nativeTickClientId = -1; // -1 = auto (nativeClientId + 1)
    private boolean nativeReadOnly    = true;
    private boolean sslVerify         = false;
    private int     connectTimeoutMs  = 5000;
    private int     readTimeoutMs     = 10000;

    public boolean isEnabled()                  { return enabled; }
    public void setEnabled(boolean v)           { enabled = v; }

    public IbkrBackendMode getMode()            { return mode; }
    public void setMode(IbkrBackendMode v)      { mode = v; }

    public String getGatewayUrl()               { return gatewayUrl; }
    public void setGatewayUrl(String v)         { gatewayUrl = v; }

    public String getNativeHost()               { return nativeHost; }
    public void setNativeHost(String v)         { nativeHost = v; }

    public int getNativePort()                  { return nativePort; }
    public void setNativePort(int v)            { nativePort = v; }

    public int getNativeClientId()              { return nativeClientId; }
    public void setNativeClientId(int v)        { nativeClientId = v; }

    public int getNativeTickClientId()          { return nativeTickClientId < 0 ? nativeClientId + 1 : nativeTickClientId; }
    public void setNativeTickClientId(int v)    { nativeTickClientId = v; }

    public boolean isNativeReadOnly()           { return nativeReadOnly; }
    public void setNativeReadOnly(boolean v)    { nativeReadOnly = v; }

    public boolean isSslVerify()                { return sslVerify; }
    public void setSslVerify(boolean v)         { sslVerify = v; }

    public int getConnectTimeoutMs()            { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v)      { connectTimeoutMs = v; }

    public int getReadTimeoutMs()               { return readTimeoutMs; }
    public void setReadTimeoutMs(int v)         { readTimeoutMs = v; }
}
