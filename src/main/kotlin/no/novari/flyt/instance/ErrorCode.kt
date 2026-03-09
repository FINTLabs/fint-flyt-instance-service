package no.novari.flyt.instance

enum class ErrorCode {
    GENERAL_SYSTEM_ERROR,
    ;

    fun getCode(): String {
        return ERROR_PREFIX + name
    }

    private companion object {
        private const val ERROR_PREFIX = "FINT_FLYT_INSTANCE_SERVICE_"
    }
}
