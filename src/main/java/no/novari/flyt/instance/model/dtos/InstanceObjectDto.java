package no.novari.flyt.instance.model.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.jackson.Jacksonized;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Getter
@Builder
@EqualsAndHashCode
@Jacksonized
public class InstanceObjectDto {

    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long id;

    @Builder.Default
    private Map<String, String> valuePerKey = new HashMap<>();

    @Builder.Default
    private Map<String, Collection<InstanceObjectDto>> objectCollectionPerKey = new HashMap<>();

    private Date createdAt;
}
