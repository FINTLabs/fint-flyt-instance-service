package no.fintlabs;

public class NoInstanceFlowHeadersException extends RuntimeException {

    public NoInstanceFlowHeadersException(String instanceId) {
        super("Could not find instance flow headers for registered instance with id=" + instanceId);
    }

}
