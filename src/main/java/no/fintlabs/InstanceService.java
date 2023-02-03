package no.fintlabs;

import no.fintlabs.model.instance.InstanceMapperService;
import no.fintlabs.model.instance.dtos.InstanceElementDto;
import org.springframework.stereotype.Service;

@Service
public class InstanceService {

    private final InstanceRepository instanceRepository;
    private final InstanceMapperService instanceMapperService;

    public InstanceService(InstanceRepository instanceRepository, InstanceMapperService instanceMapperService) {
        this.instanceRepository = instanceRepository;
        this.instanceMapperService = instanceMapperService;
    }

    public InstanceElementDto save(InstanceElementDto instanceElementDto) {
        return instanceMapperService.toInstanceElementDto(
                instanceRepository.save(instanceMapperService.toInstanceElement(instanceElementDto))
        );
    }

    public InstanceElementDto getById(Long instanceId) {
        return instanceMapperService.toInstanceElementDto(instanceRepository.getById(instanceId));
    }

}
