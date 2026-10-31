package de.drremote.dsp408.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.parameters.BlockData;
import de.drremote.dsp408.parameters.ParameterKind;
import de.drremote.dsp408.parameters.ParameterValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChangePlannerTest {
    private static final String VERSION="FIR408 V0113P";

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

    private ChangePlan plan(Map<Integer,byte[]> baseline,ParameterChange... changes) {
        return ChangePlanner.plan(VERSION,BlockData.of(baseline),new ChangeRequest(VERSION,List.of(changes)));
    }

    @Test void preparesGoldenPayloadsAndPreservesRequestedOrder() throws Exception {
        var result=plan(reference(),new ParameterChange(5,ParameterKind.GAIN,-3),
                new ParameterChange(11,ParameterKind.DELAY,20),new ParameterChange(3,ParameterKind.MUTE,1));
        assertEquals(3,result.readyCount());assertEquals(0,result.blockedCount());
        assertEquals(List.of("00 01 04 34 05 FA 00","00 01 04 38 0B 80 07","00 01 03 35 03 01"),
                result.entries().stream().map(ChangePlan.Entry::payloadHex).toList());
        assertEquals(-6,result.entries().getFirst().before().value());
        assertEquals(List.of("Out2","Out8","InD"),result.entries().stream().map(e->e.change().channel()).toList());
    }

    @Test void encodedNoOpsDoNotCreateCommandsEvenWhenRequestedNumbersDiffer() throws Exception {
        var result=plan(reference(),new ParameterChange(5,ParameterKind.GAIN,-6.01),new ParameterChange(11,ParameterKind.DELAY,0.001));
        assertEquals(0,result.readyCount());assertEquals(2,result.unchangedCount());
        assertEquals(-6,result.entries().getFirst().change().targetValue());
        assertEquals(-6.01,result.entries().getFirst().change().value());
        assertTrue(result.entries().stream().allMatch(e->e.payloadHex().isEmpty()));
    }

    @Test void muteComparesTheConfirmedBitInsteadOfTheWholeMaskByte() throws Exception {
        var blocks=reference();blocks.get(28)[34]=(byte)0x81;
        var result=plan(blocks,new ParameterChange(11,ParameterKind.MUTE,1));
        assertEquals(1,result.unchangedCount());assertEquals(0,result.readyCount());
        assertEquals(129,result.entries().getFirst().before().raw());
        assertEquals(1,result.entries().getFirst().before().value());
        assertEquals("",result.entries().getFirst().payloadHex());
    }

    @Test void inferredMuteIsBlockedDespiteKnownWriteLayout() throws Exception {
        var entry=plan(reference(),new ParameterChange(0,ParameterKind.MUTE,1)).entries().getFirst();
        assertEquals(ChangePlan.Status.BLOCKED,entry.status());
        assertEquals(ParameterValue.Status.INFERRED,entry.before().status());
        assertTrue(entry.reason().contains("INFERRED"));assertEquals("",entry.payloadHex());
    }

    @Test void missingAndInvalidBaselinesProduceNoPayload() throws Exception {
        var blocks=reference();blocks.remove(15);blocks.get(28)[28]=(byte)255;blocks.get(28)[29]=(byte)255;
        var result=plan(blocks,new ParameterChange(5,ParameterKind.GAIN,-3),new ParameterChange(11,ParameterKind.DELAY,20));
        assertEquals(2,result.blockedCount());assertEquals(0,result.readyCount());
        assertEquals(ParameterValue.Status.UNKNOWN,result.entries().get(0).before().status());
        assertEquals(ParameterValue.Status.INVALID,result.entries().get(1).before().status());
        assertTrue(result.entries().stream().allMatch(e->e.payloadHex().isEmpty()));
    }

    @Test void unknownFirmwareAndDspInferredDelayCannotPrepareCommands() throws Exception {
        var unknown=ChangePlanner.plan("FIR408 V0115",BlockData.of(reference()),
                new ChangeRequest("FIR408 V0115",List.of(new ParameterChange(5,ParameterKind.GAIN,-3))));
        assertEquals(1,unknown.blockedCount());assertEquals("",unknown.entries().getFirst().payloadHex());
        String report=ChangePlanReport.text(unknown);
        assertTrue(report.contains("Rohwert Speicher: unbekannt"),report);
        assertTrue(report.contains("Lesenachweis: unbekannt"),report);
        assertTrue(report.contains("Schreibnachweis: unbekannt"),report);
        var dsp=ChangePlanner.plan("DSP408 V0104",BlockData.of(reference()),
                new ChangeRequest("DSP408 V0104",List.of(new ParameterChange(1,ParameterKind.DELAY,20))));
        assertEquals(1,dsp.blockedCount());assertEquals("",dsp.entries().getFirst().payloadHex());
    }

    @Test void firmwareMismatchRejectsTheWholeRequest() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ChangePlanner.plan(VERSION,BlockData.of(reference()),
                new ChangeRequest("FIR408 V0115",List.of(new ParameterChange(5,ParameterKind.GAIN,-3)))));
    }

    @Test void duplicateParametersAreRejectedAndRequestsAreDefensivelyCopied() throws Exception {
        var change=new ParameterChange(5,ParameterKind.GAIN,-3);
        assertThrows(IllegalArgumentException.class,()->new ChangeRequest(VERSION,List.of(change,change)));
        var mutable=new ArrayList<>(List.of(change));var request=new ChangeRequest(VERSION,mutable);mutable.clear();
        var result=ChangePlanner.plan(VERSION,BlockData.of(reference()),request);
        assertEquals(1,result.readyCount());
        assertThrows(UnsupportedOperationException.class,()->result.entries().clear());
        assertThrows(IllegalArgumentException.class,()->new ChangeRequest(" ",List.of(change)));
        assertThrows(IllegalArgumentException.class,()->new ParameterChange(12,ParameterKind.GAIN,0));
    }

    @ParameterizedTest @CsvSource({"GAIN,-61","GAIN,12.1","GAIN,NaN","GAIN,Infinity","DELAY,-1","DELAY,680.1","MUTE,0.5","MUTE,2"})
    void invalidTargetsCannotEnterAPlan(ParameterKind kind,double value) {
        assertThrows(IllegalArgumentException.class,()->new ParameterChange(0,kind,value));
    }

    @Test void emptyRequestProducesAnEmptyPlan() throws Exception {
        var result=plan(reference());
        assertEquals(0,result.readyCount());assertEquals(0,result.blockedCount());assertTrue(result.entries().isEmpty());
    }
}
