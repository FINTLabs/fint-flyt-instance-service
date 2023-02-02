package no.fintlabs.model.instance.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstanceElementDto {

    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long id;

    private Map<String, String> valuePerKey = new HashMap<>();

    private Map<String, Collection<InstanceElementDto>> elementCollectionPerKey = new HashMap<>();

}
