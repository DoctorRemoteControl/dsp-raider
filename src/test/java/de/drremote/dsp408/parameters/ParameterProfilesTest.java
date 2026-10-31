package de.drremote.dsp408.parameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParameterProfilesTest {
    private final ParameterProfile fir=ParameterProfiles.forVersion("FIR408 V0113P").orElseThrow();

    private Map<Integer,byte[]> reference() throws Exception {
        try(var input=getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) {
            var root=new ObjectMapper().readTree(input);var blocks=new HashMap<Integer,byte[]>();
            for(var block:root.path("blocks")) {
                byte[] payload=java.util.HexFormat.of().parseHex(block.path("payload_hex").asText().replace(" ",""));
                blocks.put(Integer.decode(block.path("block").asText()),java.util.Arrays.copyOfRange(payload,5,payload.length));
            }
            return blocks;
        }
    }

    @Test void exactVersionCatalogHasAll36ChannelDefinitions() {
        assertEquals(36,fir.definitions().size());
        assertEquals(36,fir.definitions().stream().map(ParameterDefinition::id).distinct().count());
        assertTrue(ParameterProfiles.forVersion("FIR408 V0115").isEmpty());
        assertTrue(ParameterProfiles.forVersion("UNKNOWN").isEmpty());
        assertEquals(new ReadLocation(2,46,2,0),fir.definition(0,ParameterKind.GAIN).readLocation());
        assertEquals(new ReadLocation(13,18,2,0),fir.definition(4,ParameterKind.GAIN).readLocation());
        assertEquals(new ReadLocation(28,28,2,0),fir.definition(11,ParameterKind.DELAY).readLocation());
        var dsp=ParameterProfiles.forVersion("DSP408 V0104").orElseThrow();
        assertEquals(new ReadLocation(2,48,2,0),dsp.definition(0,ParameterKind.GAIN).readLocation());
    }

    @Test void realFixtureUsesVerifiedGainAndDelayAnchors() throws Exception {
        BlockData data=BlockData.of(reference());
        double[] db={-16,-16,-13.5,-13.5,-1,-6,-6,-6,-14,-14,-14,-14};
        for(int channel=0;channel<12;channel++) {
            var gain=fir.definition(channel,ParameterKind.GAIN).read(data);
            var delay=fir.definition(channel,ParameterKind.DELAY).read(data);
            assertEquals(ParameterValue.Status.CONFIRMED,gain.status());
            assertEquals(db[channel],gain.value(),1e-9);
            assertEquals(ParameterValue.Status.CONFIRMED,delay.status());
            assertEquals(0,delay.value(),1e-9);
        }
    }

    @Test void onlyFirSpecificMuteBitsAreConfirmed() throws Exception {
        var blocks=reference();blocks.get(28)[32]=9;blocks.get(28)[34]=(byte)0x88;
        var data=BlockData.of(blocks);
        var ina=fir.definition(0,ParameterKind.MUTE).read(data);
        assertEquals(ParameterValue.Status.INFERRED,ina.status());
        assertNull(ina.value());
        assertEquals(1,fir.definition(3,ParameterKind.MUTE).read(data).value(),1e-9);
        assertEquals(1,fir.definition(11,ParameterKind.MUTE).read(data).value(),1e-9);
        assertEquals(new ReadLocation(28,32,1,8),fir.definition(3,ParameterKind.MUTE).readLocation());
        assertTrue(fir.definition(3,ParameterKind.MUTE).readEvidence().source().contains("DSP408Controller"));
    }

    @Test void missingDataAndInvalidValuesStayExplicit() throws Exception {
        var blocks=reference();blocks.remove(2);
        var missing=fir.definition(0,ParameterKind.GAIN).read(BlockData.of(blocks));
        assertEquals(ParameterValue.Status.UNKNOWN,missing.status());assertNull(missing.value());
        blocks=reference();blocks.get(13)[18]=(byte)255;blocks.get(13)[19]=(byte)255;
        var invalid=fir.definition(4,ParameterKind.GAIN).read(BlockData.of(blocks));
        assertEquals(ParameterValue.Status.INVALID,invalid.status());assertEquals(65535,invalid.raw());assertNull(invalid.value());
    }

    @Test void capturedDataIsNotChangedByCallerMutation() throws Exception {
        var blocks=reference();var data=BlockData.of(blocks);
        blocks.get(13)[18]=0;
        assertEquals(-1,fir.definition(4,ParameterKind.GAIN).read(data).value(),1e-9);
    }

    @ParameterizedTest @CsvSource({"DSP408 V0104","FIR408 V0113P"})
    void everyCatalogPositionMatchesThePreservedSourceEvidence(String version) throws Exception {
        try(var input=getClass().getResourceAsStream("/parameters/channel-locations.json")) {
            assertNotNull(input);
            byte[] fixture=input.readAllBytes();
            assertEquals("27ee9e78366ac1859baf59575f6726b1f82693732fb7189dff775ee367faf037",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)),"Evidence fixture integrity");
            var sources=new ObjectMapper().readTree(fixture).path("sources");
            var profile=ParameterProfiles.forVersion(version).orElseThrow();
            for(var definition:profile.definitions()) {
                String reference=definition.readEvidence().source();int anchor=reference.indexOf('#');
                var evidence=sources.path(reference.substring(0,anchor)).path("fragments").path(reference.substring(anchor+1));
                assertFalse(evidence.isMissingNode(),reference);
                var position=definition.readLocation();
                assertEquals(Integer.parseInt(evidence.path("block_hex").asText().replace("0x",""),16),position.block(),definition.id());
                assertEquals(evidence.path("offset_dec").asInt()-5,position.dataOffset(),definition.id());
                if(definition.kind()==ParameterKind.DELAY) {
                    boolean observed=evidence.path("status").asText().startsWith("observed");
                    assertEquals(observed,definition.readEvidence().level()==Evidence.Level.CONFIRMED,definition.id());
                }
            }
        }
    }
}
