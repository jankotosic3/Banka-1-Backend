package com.banka1.clientService.controller;

import com.banka1.clientService.advice.GlobalExceptionHandler;
import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientInfoResponseDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.service.ClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClientController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class ClientControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ClientService clientService;

    @Test
    void searchClientsWithFiltersReturnsPagedResponse() throws Exception {
        ClientResponseDto client = sampleResponse();

        when(clientService.searchClients(eq("Petar"), eq("Petrovic"), eq("petar@banka.com"), any()))
                .thenReturn(new PageImpl<>(List.of(client), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/customers")
                        .param("ime", "Petar")
                        .param("prezime", "Petrovic")
                        .param("email", "petar@banka.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("petar@banka.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchClientsWithNoFiltersReturnsAll() throws Exception {
        when(clientService.searchClients(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void createClientReturnsCreatedForValidPayload() throws Exception {
        ClientCreateRequestDto request = validCreateRequest();
        when(clientService.createClient(any(ClientCreateRequestDto.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("petar@banka.com"));
    }

    @Test
    void createClientReturnsBadRequestForMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.ime").exists())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.jmbg").exists());
    }

    @Test
    void createClientReturnsBadRequestForInvalidJmbg() throws Exception {
        ClientCreateRequestDto request = validCreateRequest();
        request.setJmbg("123");

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.jmbg").exists());
    }

    @Test
    void updateClientReturnsOkForValidPayload() throws Exception {
        ClientUpdateRequestDto request = new ClientUpdateRequestDto();
        request.setIme("Petar");
        request.setPrezime("NovoPrezime");

        when(clientService.updateClient(eq(1L), any(ClientUpdateRequestDto.class))).thenReturn(sampleResponse());

        mockMvc.perform(put("/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("petar@banka.com"));
    }

    @Test
    void updateClientReturnsBadRequestForInvalidEmail() throws Exception {
        ClientUpdateRequestDto request = new ClientUpdateRequestDto();
        request.setEmail("nije-email");

        mockMvc.perform(put("/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void updateClientReturnsNotFoundWhenClientMissing() throws Exception {
        ClientUpdateRequestDto request = new ClientUpdateRequestDto();
        request.setIme("Petar");
        request.setPrezime("X");

        when(clientService.updateClient(eq(99L), any(ClientUpdateRequestDto.class)))
                .thenThrow(new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: 99"));

        mockMvc.perform(put("/customers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").exists());
    }

    @Test
    void deleteClientReturnsNoContent() throws Exception {
        doNothing().when(clientService).deleteClient(1L);

        mockMvc.perform(delete("/customers/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteClientReturnsNotFoundWhenClientMissing() throws Exception {
        doThrow(new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: 99"))
                .when(clientService).deleteClient(99L);

        mockMvc.perform(delete("/customers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").exists());
    }

    @Test
    void globalSearchReturnsPagedResults() throws Exception {
        when(clientService.globalSearchClients(eq("petar"), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/customers/search")
                        .param("query", "petar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("petar@banka.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getClientIdByJmbgReturnsId() throws Exception {
        when(clientService.getInfoByJmbg("1234567890123")).thenReturn(new ClientInfoResponseDto(42L,"Pickka","Pickic"));

        mockMvc.perform(get("/customers/jmbg/1234567890123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void getClientIdByJmbgReturnsNotFoundWhenJmbgMissing() throws Exception {
        doThrow(new BusinessException(ErrorCode.JMBG_NOT_FOUND, "JMBG: [PROTECTED]"))
                .when(clientService).getInfoByJmbg("9999999999999");

        mockMvc.perform(get("/customers/jmbg/9999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").exists());
    }

    private ClientResponseDto sampleResponse() {
        return new ClientResponseDto(1L, "Petar", "Petrovic", 641520000000L, Pol.M,
                "petar@banka.com", "+381641234567", "Njegoseva 25", ClientRole.CLIENT_BASIC);
    }

    private ClientCreateRequestDto validCreateRequest() {
        ClientCreateRequestDto dto = new ClientCreateRequestDto();
        dto.setIme("Petar");
        dto.setPrezime("Petrovic");
        dto.setDatumRodjenja(641520000000L);
        dto.setPol(Pol.M);
        dto.setEmail("petar@banka.com");
        dto.setBrojTelefona("+381641234567");
        dto.setAdresa("Njegoseva 25");
        dto.setJmbg("1234567890123");
        return dto;
    }
}
