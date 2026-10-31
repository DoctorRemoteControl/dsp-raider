package de.drremote.dsp408.fir;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.DspPayloads;
import de.drremote.dsp408.session.DspSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Fir408DiagnosticsTest {
    @TempDir Path directory;
    private final List<byte[]> writes=new ArrayList<>();
    private DspSession session() {
        return (DspSession)Proxy.newProxyInstance(DspSession.class.getClassLoader(),new Class<?>[]{DspSession.class},
            (proxy,method,args) -> {
                if (!method.getName().equals("sendPayload")) throw new AssertionError(method.getName());
                byte[] request=((byte[])args[0]).clone(); writes.add(request);
                byte[] response={1,0,8,0x56,request[4],request[5],request[6],(byte)255,(byte)255,(byte)255,(byte)255};
                return new ProxyResponse(new byte[0],response,true);
            });
    }

    @Test void probesOnlyRequestedOffsetsAndWritesDiagnosticsToChosenDirectory() throws Exception {
        String report=new Fir408Diagnostics(session()).readOnlyOffsets(directory.toString(),2,"0,0x10",1);
        assertEquals(2,writes.size());
        assertTrue(writes.stream().allMatch(p -> DspPayloads.command(p)==0x56));
        assertEquals(16,writes.getLast()[5]);
        assertTrue(report.contains("FF rows: 2"));
        assertTrue(Files.isRegularFile(directory.resolve("results.csv")));
        assertTrue(Files.isRegularFile(directory.resolve("responses/sel-02-off-0016.hex.txt")));
    }

    @Test void invalidOffsetsCauseNoDeviceTraffic() {
        assertThrows(IllegalArgumentException.class,
            () -> new Fir408Diagnostics(session()).readOnlyOffsets(directory.toString(),0,"65536",1));
        assertTrue(writes.isEmpty());
    }
}
