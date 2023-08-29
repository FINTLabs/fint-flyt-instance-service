package no.fintlabs.model.instance.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.util.Map;

@Getter
@Setter
@EqualsAndHashCode
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class InstanceObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private long id;

    @ElementCollection
    @JoinColumn(name = "instance_object_id")
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    private Map<String, String> valuePerKey;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "instance_object_id")
    @MapKeyColumn(name = "key")
    private Map<String, InstanceObjectCollection> objectCollectionPerKey;

    @Override
    public String toString() {
        return "Sensitive data omitted";
    }

}
