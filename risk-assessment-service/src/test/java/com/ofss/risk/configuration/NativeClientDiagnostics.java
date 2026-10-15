package com.ofss.risk.configuration;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.channels.*;
import java.time.Duration;
import java.nio.file.*;

/** Standalone JDK-only diagnostics: no application, credentials, environment dump or external connections. */
public final class NativeClientDiagnostics {
    private NativeClientDiagnostics() {}
    public static void main(String[] args) {
        System.out.println("JDK="+System.getProperty("java.version")+"; vendor="+System.getProperty("java.vendor")+"; OS="+System.getProperty("os.name"));
        System.out.println("java-temp-path-length="+System.getProperty("java.io.tmpdir").length()+"; explicit-uds-temp="+(System.getProperty("jdk.net.unixdomain.tmpdir")!=null));
        probe("selector-pipe",()->{var pipe=Pipe.open();try(var source=pipe.source();var sink=pipe.sink()) {}});
        probe("ipv4-loopback",()->{try(var server=ServerSocketChannel.open(StandardProtocolFamily.INET)){
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
            try(var client=SocketChannel.open(StandardProtocolFamily.INET)){client.connect(server.getLocalAddress());try(var accepted=server.accept()) {}}
        }});
        probe("unix-auto-address",()->{Path socket=null;try {
            try(var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)){
                server.bind(null);var address=(UnixDomainSocketAddress)server.getLocalAddress();socket=address.getPath();
                System.out.println("  auto-uds-path-length="+socket.toString().length());
                try(var client=SocketChannel.open(address);var accepted=server.accept()) {}
            }
        }finally {if(socket!=null)try{Files.deleteIfExists(socket);}catch(java.io.IOException cleanup){System.out.println("  diagnostic-socket-cleanup="+cleanup.getClass().getSimpleName());}}});
        probe("native-http-client",()->{try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build()) {}});
    }
    private static void probe(String label,Checked task){
        try{task.run();System.out.println(label+"=PASS");}
        catch(Exception e){System.out.println(label+"=FAIL");for(Throwable cause=e;cause!=null;cause=cause.getCause()){
            // These operations have no request/token/payload and only JDK socket messages; never use this helper on application errors.
            System.out.println("  "+cause.getClass().getName()+": "+cause.getMessage());
            for(var frame:cause.getStackTrace())if(frame.getClassName().startsWith("sun.nio.ch.")||frame.getClassName().startsWith("jdk.internal.net.http."))System.out.println("    "+frame);
        }}
    }
    private interface Checked{void run()throws Exception;}
}
