package com.bookmyshow.catalog.movie;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.context.annotation.Import(com.bookmyshow.catalog.security.SecurityConfiguration.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@WebMvcTest(MovieController.class)
class MovieControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MovieService service;

    @Test void invalidBodyReturnsFieldErrorsWithoutCallingService() throws Exception {
        mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \",\"durationMinutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.durationMinutes").exists())
                .andExpect(jsonPath("$.errors.active").exists());
        verifyNoInteractions(service);
    }

    @Test void missingMovieReturnsProblemDetails() throws Exception {
        when(service.getById(99L)).thenThrow(new ResourceNotFoundException("Movie", 99L));
        mvc.perform(get("/api/v1/movies/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Movie with id 99 was not found"));
    }

    @Test void invalidPaginationIsRejected() throws Exception {
        mvc.perform(get("/api/v1/movies?page=-1&size=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void malformedJsonIsRejected() throws Exception {
        mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/movies/0")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
