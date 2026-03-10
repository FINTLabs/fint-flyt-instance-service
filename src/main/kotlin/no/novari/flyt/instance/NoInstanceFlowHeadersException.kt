package no.novari.flyt.instance

class NoInstanceFlowHeadersException(
    instanceId: Long,
) : RuntimeException("Could not find instance flow headers for registered instance with id=$instanceId")
