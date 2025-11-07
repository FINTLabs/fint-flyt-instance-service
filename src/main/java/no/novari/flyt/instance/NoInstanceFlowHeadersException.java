package no.novari.flyt.instance;

public class NoInstanceFlowHeadersException extends RuntimeException {

    public NoInstanceFlowHeadersException(Long instanceId) {
        super("Could not find instance flow headers for registered instance with id=" + instanceId);
    }

}
