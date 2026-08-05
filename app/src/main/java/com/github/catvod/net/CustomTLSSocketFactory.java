package com.github.catvod.net;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public class CustomTLSSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    
    private static final String[] CIPHER_SUITES = new String[]{
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    };

    public CustomTLSSocketFactory() throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, null, null);
        this.delegate = context.getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() { return CIPHER_SUITES; }

    @Override
    public String[] getSupportedCipherSuites() { return CIPHER_SUITES; }

    private Socket enableTLS(Socket socket) {
        if (socket instanceof SSLSocket) {
            ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            ((SSLSocket) socket).setEnabledCipherSuites(CIPHER_SUITES);
        }
        return socket;
    }

    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return enableTLS(delegate.createSocket(s, host, port, autoClose)); }
    @Override public Socket createSocket(String host, int port) throws IOException { return enableTLS(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return enableTLS(delegate.createSocket(host, port, localHost, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return enableTLS(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return enableTLS(address, port, localAddress, localPort); }
}