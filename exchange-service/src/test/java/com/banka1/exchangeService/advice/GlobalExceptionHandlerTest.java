package com.banka1.exchangeService.advice;

import com.banka1.exchangeService.exception.BusinessException;
import com.banka1.exchangeService.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testovi centralizovanog exception handler-a za REST sloj.
 * Ovi testovi potvrdjuju da servis vraca stabilan i predvidiv JSON error
 * format za biznis greske, validacione greske i neocekivane izuzetke.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /**
     * Priprema standalone MockMvc sa test kontrolerom i pravim advice bean-om.
     * Time se izolovano proverava samo mapiranje izuzetaka, bez ostatka Spring
     * web sloja.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Proverava da se domen-specifican BusinessException mapira na odgovarajuci
     * HTTP status i standardizovan payload.
     * Prolaz znaci da klijenti dobijaju pravi kod greske za "not found" scenario.
     */
    @Test
    void businessExceptionReturnsMappedStatusAndPayload() throws Exception {
        mockMvc.perform(get("/test-business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_EXCHANGE_RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("missing rate"));
    }

    /**
     * Proverava da validacija request body-ja vraca ERR_VALIDATION i mapu polja
     * koja nisu prosla proveru.
     * Prolaz znaci da frontend i ostali klijenti mogu precizno da prikazu
     * korisniku sta nije validno.
     */
    @Test
    void validationExceptionReturnsValidationErrors() throws Exception {
        mockMvc.perform(post("/test-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_VALIDATION"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    /**
     * Proverava da neocekivani RuntimeException ne procuri kao stacktrace u API
     * odgovor, vec da se vrati genericki 500 payload.
     * Prolaz znaci da servis cuva stabilan ugovor i pri internim greskama.
     */
    @Test
    void unexpectedExceptionReturnsInternalServerError() throws Exception {
        mockMvc.perform(get("/test-unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ERR_INTERNAL_SERVER"));
    }

    /**
     * Minimalni test kontroler koji namerno proizvodi razlicite tipove gresaka
     * kako bi se proverilo ponasanje GlobalExceptionHandler-a.
     */
    @RestController
    static class TestController {

        @GetMapping("/test-business")
        public void business() {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND, "missing rate");
        }

        @GetMapping("/test-unexpected")
        public void unexpected() {
            throw new RuntimeException("boom");
        }

        @PostMapping("/test-validation")
        public String validate(@RequestBody @Valid RequestDto dto) {
            return dto.name();
        }
    }

    /**
     * Minimalni request DTO za validacioni scenario.
     */
    record RequestDto(@NotBlank String name) {
    }
}
