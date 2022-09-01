package no.fintlabs;

public enum ErrorCode {
    GENERAL_SYSTEM_ERROR;

    private static final String ERROR_PREFIX = "FINT_FLYT_INSTANCE_SERVICE_";

    public String getCode() {
        return ERROR_PREFIX + name();
    }

}
