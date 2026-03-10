package no.novari.flyt.instance.model.dtos

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class InstanceObjectDto(
    @field:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val id: Long? = null,
    val valuePerKey: MutableMap<String, String> = mutableMapOf(),
    val objectCollectionPerKey: MutableMap<String, Collection<InstanceObjectDto>> = mutableMapOf(),
    val createdAt: Date? = null,
)
