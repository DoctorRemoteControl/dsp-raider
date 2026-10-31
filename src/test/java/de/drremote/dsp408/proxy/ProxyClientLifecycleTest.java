package de.drremote.dsp408.proxy;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import static org.junit.jupiter.api.Assertions.*;

class ProxyClientLifecycleTest {
    @Test void closesStreamConnectionWhenControlConnectionFails() throws Exception {
        var loopback=InetAddress.getLoopbackAddress();
        try (var stream=new ServerSocket(0,1,loopback)) {
            int unavailable;
            try (var reservation=new ServerSocket(0,1,loopback)) { unavailable=reservation.getLocalPort(); }
            try (var client=new ProxyClient(ProxyConfig.of(loopback.getHostAddress(),stream.getLocalPort(),
                    loopback.getHostAddress(),unavailable,null,false))) {
                assertThrows(IOException.class,client::connect);
                try (var accepted=stream.accept()) {
                    accepted.setSoTimeout(1000);
                    assertEquals(-1,accepted.getInputStream().read());
                }
            }
        }
    }
}
