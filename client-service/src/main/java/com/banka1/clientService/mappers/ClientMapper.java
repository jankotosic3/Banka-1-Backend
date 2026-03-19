package com.banka1.clientService.mappers;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct mapper za konverziju izmedju DTO objekata i JPA entiteta {@link Klijent}.
 * Implementacija se generiše u vreme kompajliranja — nemapovana ciljna polja uzrokuju gresku pri build-u.
 * Normalizacija broja telefona je enkapsulirana u {@link Klijent#setBrojTelefona}.
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ClientMapper {

    /**
     * Mapira DTO za kreiranje klijenta u entitet.
     * BaseEntity polja (id, version, deleted, timestamps) i kredencijali se postavljaju zasebno.
     */
    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "version",      ignore = true)
    @Mapping(target = "deleted",      ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "password",     ignore = true)
    @Mapping(target = "role",         ignore = true)
    Klijent toEntity(ClientCreateRequestDto dto);

    /**
     * Mapira entitet klijenta u izlazni DTO.
     * Osetljivi podaci (password, jmbg) nisu u {@link ClientResponseDto} — automatski se preskacu.
     */
    ClientResponseDto toDto(Klijent klijent);

    /**
     * Parcijalno azurira entitet klijenta podacima iz DTO-a.
     * Null vrednosti u DTO-u se preskacu — polje ostaje nepromenjeno.
     * JMBG i kredencijali su nepromenjivi i uvek se ignorisu.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "version",      ignore = true)
    @Mapping(target = "deleted",      ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "password",     ignore = true)
    @Mapping(target = "jmbg",         ignore = true)
    @Mapping(target = "role",         ignore = true)
    void updateEntityFromDto(@MappingTarget Klijent klijent, ClientUpdateRequestDto dto);
}
