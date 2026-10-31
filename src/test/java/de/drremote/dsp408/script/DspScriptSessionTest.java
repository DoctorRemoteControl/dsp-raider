package de.drremote.dsp408.script;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.session.DspSession;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DspScriptSessionTest {
    private final List<String> calls = new ArrayList<>();

    private DspSession session(boolean failConnect, boolean failClose) {
        return (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(), new Class<?>[]{DspSession.class},
            (proxy, method, args) -> {
                calls.add(method.getName());
                if (method.getName().equals("connect") && failConnect) throw new IOException("connect failed");
                if (method.getName().equals("close") && failClose) throw new IOException("close failed");
                if (method.getName().equals("readBlock")) {
                    byte[] payload = {1,0,3,0x24,((Integer)args[0]).byteValue(),0x55};
                    return new ProxyResponse(DspFrameCodec.encode(payload),payload,true);
                }
                return null;
            });
    }

    private DspScriptEngine engine(boolean failConnect, boolean failClose) {
        return new DspScriptEngine("stream.test",19081,"control.test",19082,false,
            (streamHost,streamPort,controlHost,controlPort,verbose) -> {
                assertEquals("stream.test",streamHost);
                assertEquals(19081,streamPort);
                assertEquals("control.test",controlHost);
                assertEquals(19082,controlPort);
                return session(failConnect,failClose);
            });
    }

    @Test void runsLegacyAndFunctionSyntaxThroughInjectedSession() throws Exception {
        engine(false,false).executeText("""
                connect
                handshake
                let block = read-block 0
                assert cmd(block) == 0x24
                let again = readBlock(1)
                assert readBlockIndex(again) == 1
                disconnect
                """);
        assertEquals(List.of("connect","handshake","readBlock","readBlock","close"),calls);
    }

    @Test void closesSessionWhenScriptFails() {
        assertThrows(IllegalStateException.class, () -> engine(false,false).executeText("connect\nassert 1 == 2"));
        assertEquals(List.of("connect","close"),calls);
    }

    @Test void closesPartiallyConnectedSessionAndKeepsPrimaryError() {
        var wrapped = assertThrows(IllegalStateException.class, () -> engine(true,true).executeText("connect"));
        IOException error = assertInstanceOf(IOException.class, wrapped.getCause());
        assertEquals("connect failed",error.getMessage());
        assertEquals("close failed",error.getSuppressed()[0].getMessage());
        assertEquals(List.of("connect","close"),calls);
    }

    @Test void keepsPrimaryScriptErrorWhenClosingFails() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine(false,true).executeText("connect\nassert 1 == 2"));
        assertEquals("close failed",error.getSuppressed()[0].getMessage());
    }
}
