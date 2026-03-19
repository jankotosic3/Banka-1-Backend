package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.rabbitmq.EmailDto;
import com.banka1.clientService.dto.rabbitmq.EmailType;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientInfoResponseDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.mappers.ClientMapper;
import com.banka1.clientService.rabbitMQ.RabbitClient;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.ClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Implementacija {@link ClientService} koja upravlja CRUD operacijama nad entitetom klijenta.
 * Sve pretrage koriste LIKE escapovanje radi zastite od SQL injection putem metakaraktera.
 * Email notifikacije se salju asinhorno putem RabbitMQ-a tek nakon uspesnog commita transakcije.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ClientServiceImplementation implements ClientService {

    /**
     * Repozitorijum za pristup entitetima klijenata.
     */
    private final KlijentRepository klijentRepository;

    /**
     * Mapper za konverziju izmedju DTO i JPA entiteta klijenta.
     */
    private final ClientMapper clientMapper;

    /**
     * Klijent za slanje email notifikacija putem RabbitMQ-a.
     */
    private final RabbitClient rabbitClient;

    /**
     * Kreira novog klijenta i salje notifikacioni mejl nakon uspesnog commita transakcije.
     *
     * @param dto podaci za kreiranje klijenta
     * @return kreirani klijent mapiran u odgovor
     */
    @Override
    public ClientResponseDto createClient(ClientCreateRequestDto dto) {
        Klijent klijent = clientMapper.toEntity(dto);
        Klijent saved = klijentRepository.save(klijent);

        EmailDto emailDto = new EmailDto(
                saved.getIme(),
                saved.getEmail(),
                EmailType.CLIENT_CREATED);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitClient.sendEmailNotification(emailDto);
            }
        });

        return clientMapper.toDto(saved);
    }

    /**
     * Pretrazuje klijente po kombinaciji filtera uz paginaciju.
     * Null vrednosti se tretiraju kao wildcard (prazan string); LIKE metakarakteri se eskejpuju.
     *
     * @param ime      filter po imenu
     * @param prezime  filter po prezimenu
     * @param email    filter po email adresi
     * @param pageable parametri paginacije
     * @return stranica klijenata mapirana u DTO objekte
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ClientResponseDto> searchClients(String ime, String prezime, String email, Pageable pageable) {
        String safeIme = (ime != null) ? escapeLike(ime) : "";
        String safePrezime = (prezime != null) ? escapeLike(prezime) : "";
        String safeEmail = (email != null) ? escapeLike(email) : "";

        return klijentRepository.searchClients(safeIme, safePrezime, safeEmail, pageable)
                .map(clientMapper::toDto);
    }

    /**
     * Vrsi globalnu pretragu klijenata jednim tekstualnim upitom.
     * Upit se poredi sa imenom, prezimenom i emailom.
     *
     * @param query    tekstualni upit za pretragu
     * @param pageable parametri paginacije
     * @return stranica rezultata mapirana u DTO objekte
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ClientResponseDto> globalSearchClients(String query, Pageable pageable) {
        String safeQuery = (query != null) ? escapeLike(query) : "";
        return klijentRepository.globalSearchClients(safeQuery, pageable)
                .map(clientMapper::toDto);
    }

    /**
     * Azurira podatke klijenta.
     * JMBG i password se ne mogu menjati.
     *
     * @param id  identifikator klijenta koji se menja
     * @param dto podaci za azuriranje
     * @return azurirani klijent
     * @throws BusinessException ako klijent nije pronadjen
     */
    @Override
    public ClientResponseDto updateClient(Long id, ClientUpdateRequestDto dto) {
        Klijent klijent = klijentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: " + id));

        if (dto.getEmail() != null && klijentRepository.existsByEmailAndIdNot(dto.getEmail(), id)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email: " + dto.getEmail());
        }

        clientMapper.updateEntityFromDto(klijent, dto);
        return clientMapper.toDto(klijentRepository.saveAndFlush(klijent));
    }

    /**
     * Soft-brise klijenta po identifikatoru.
     *
     * @param id identifikator klijenta koji se brise
     * @throws BusinessException ako klijent nije pronadjen
     */
    @Override
    public void deleteClient(Long id) {
        Klijent klijent = klijentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: " + id));
        klijentRepository.delete(klijent);
    }

    /**
     * Vraca ID klijenta na osnovu JMBG-a.
     *
     * @param jmbg JMBG klijenta
     * @return DTO sa ID-em klijenta
     * @throws BusinessException ako klijent sa zadatim JMBG-om nije pronadjen
     */
    @Override
    @Transactional(readOnly = true)
    public ClientInfoResponseDto getInfoByJmbg(String jmbg) {
        Klijent klijent = klijentRepository.findByJmbg(jmbg)
                .orElseThrow(() -> new BusinessException(ErrorCode.JMBG_NOT_FOUND, "JMBG: [PROTECTED]"));
        return new ClientInfoResponseDto(klijent.getId(),klijent.getIme(),klijent.getPrezime());
    }

    /**
     * Vraca osnovne informacije o klijentu na osnovu internog ID-a.
     *
     * @param id identifikator klijenta
     * @return DTO sa ID-em, imenom i prezimenom klijenta
     * @throws BusinessException ako klijent sa zadatim ID-em nije pronadjen
     */
    @Override
    @Transactional(readOnly = true)
    public ClientInfoResponseDto getInfoById(Long id) {
        Klijent klijent = klijentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: " + id));
        return new ClientInfoResponseDto(klijent.getId(), klijent.getIme(), klijent.getPrezime());
    }

    /**
     * Eskejpuje SQL LIKE metakaraktere radi sprecavanja neocekivanih wildcard podudaranja.
     *
     * @param s ulazni string koji se eskejpuje
     * @return eskejpovan string bezbedan za upotrebu u LIKE klauzuli
     */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
