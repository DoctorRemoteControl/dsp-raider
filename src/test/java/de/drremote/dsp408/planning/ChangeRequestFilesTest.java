package de.drremote.dsp408.planning;

import de.drremote.dsp408.parameters.ParameterKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChangeRequestFilesTest {
    @TempDir Path temp;

    private ChangeRequest read(String document) throws Exception {
        Path file=temp.resolve("changes.json");Files.writeString(file,document);
        return ChangeRequestFiles.read(file);
    }

    @Test void acceptsCanonicalNamesAndNumericOrBooleanMuteWithoutCoercingOtherValues() throws Exception {
        var request=read("""
                {"device_version":"FIR408 V0113P","changes":[
                  {"channel":"out2","parameter":"GAIN","value":-3.04},
                  {"channel":"InD","parameter":"mute","value":true},
                  {"channel":"OUT8","parameter":"mute","value":0},
                  {"channel":"InB","parameter":"delay","value":20.004}
                ]}
                """);
        assertEquals(4,request.changes().size());
        assertEquals(5,request.changes().get(0).channelIndex());
        assertEquals(ParameterKind.GAIN,request.changes().get(0).kind());
        assertEquals(-3.04,request.changes().get(0).value());
        assertEquals(1,request.changes().get(1).writeRaw());
        assertEquals(0,request.changes().get(2).writeRaw());
        assertEquals(20,request.changes().get(3).targetValue());
    }

    @ParameterizedTest @ValueSource(strings={
            "null", "[]", "{}",
            "{\"device_version\":113,\"changes\":[]}",
            "{\"device_version\":\"FIR408 V0113P\",\"changes\":null}",
            "{\"device_version\":\"FIR408 V0113P\",\"changes\":[],\"apply\":true}",
            "{\"device_version\":\"FIR408 V0113P\",\"device_version\":\"DSP408 V0104\",\"changes\":[]}",
            "{\"device_version\":\"FIR408 V0113P\",\"changes\":[]} {}"
    })
    void rejectsMalformedOrAmbiguousRootDocuments(String document) {
        assertThrows(Exception.class,()->read(document));
    }

    @ParameterizedTest @ValueSource(strings={
            "null", "[]", "{}",
            "{\"channel\":\"Out9\",\"parameter\":\"gain\",\"value\":0}",
            "{\"channel\":0,\"parameter\":\"gain\",\"value\":0}",
            "{\"channel\":\"Out2\",\"parameter\":\"gian\",\"value\":0}",
            "{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":\"-3\"}",
            "{\"channel\":\"Out2\",\"parameter\":\"delay\",\"value\":true}",
            "{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":null}",
            "{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":1e999}",
            "{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":0,\"unit\":\"ms\"}",
            "{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":0,\"value\":-3}"
    })
    void rejectsInvalidEntryTypesTyposAndRepeatedKeys(String entry) {
        assertThrows(Exception.class,()->read("{\"device_version\":\"FIR408 V0113P\",\"changes\":["+entry+"]}"));
    }

    @Test void rejectsSameParameterWrittenTwiceEvenWithDifferentCase() {
        assertThrows(IllegalArgumentException.class,()->read("""
                {"device_version":"FIR408 V0113P","changes":[
                  {"channel":"Out2","parameter":"gain","value":-3},
                  {"channel":"out2","parameter":"GAIN","value":-6}
                ]}
                """));
    }
}
