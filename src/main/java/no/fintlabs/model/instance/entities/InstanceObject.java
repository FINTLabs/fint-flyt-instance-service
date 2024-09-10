package no.fintlabs.model.instance.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.util.Map;

@Getter
@Setter
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
    @Type(type = "text")
    private Map<String, String> valuePerKey;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "instance_object_id")
    @MapKeyColumn(name = "key")
    private Map<String, InstanceObjectCollection> objectCollectionPerKey;

}
