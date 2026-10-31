package de.drremote.dsp408.parameters;

public record ParameterValue(ParameterDefinition definition,Integer raw,Double value,Status status,String detail) {
    public enum Status { CONFIRMED, INFERRED, UNKNOWN, INVALID }

    public String display() {
        return status==Status.CONFIRMED ? definition.kind().format(value) : "unbekannt ("+detail+")";
    }
}
