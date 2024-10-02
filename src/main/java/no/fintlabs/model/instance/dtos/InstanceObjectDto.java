package no.fintlabs.model.instance.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
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
