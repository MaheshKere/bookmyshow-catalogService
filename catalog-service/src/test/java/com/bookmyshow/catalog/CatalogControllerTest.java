package com.bookmyshow.catalog;

import com.bookmyshow.catalog.exception.*;
import com.bookmyshow.catalog.theater.*;
import com.bookmyshow.catalog.screen.*;
import com.bookmyshow.catalog.show.*;
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
@WebMvcTest({TheaterController.class, ScreenController.class, ShowController.class})
class CatalogControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TheaterService theaterService;
    @MockitoBean ScreenService screenService;
    @MockitoBean ShowService showService;

    @Test void validatesTheaterFields() throws Exception {
        mvc.perform(post("/api/v1/theaters").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.city").exists()).andExpect(jsonPath("$.errors.address").exists());
        verifyNoInteractions(theaterService);
    }

    @Test void validatesScreenCapacityAndParentId() throws Exception {
        mvc.perform(post("/api/v1/theaters/1/screens").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Screen\",\"totalSeats\":0,\"active\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.totalSeats").exists());
        mvc.perform(get("/api/v1/theaters/0/screens")).andExpect(status().isBadRequest());
        verifyNoInteractions(screenService);
    }

    @Test void validatesShowReferencesAndTimes() throws Exception {
        mvc.perform(post("/api/v1/shows").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.movieId").exists())
                .andExpect(jsonPath("$.errors.screenId").exists())
                .andExpect(jsonPath("$.errors.theaterId").exists())
                .andExpect(jsonPath("$.errors.startTime").exists())
                .andExpect(jsonPath("$.errors.endTime").exists());
        verifyNoInteractions(showService);
    }

    @Test void validatesPaginationAndFilters() throws Exception {
        for (String path : new String[]{"/api/v1/theaters", "/api/v1/theaters/1/screens", "/api/v1/shows"}) {
            mvc.perform(get(path).param("page", "-1").param("size", "101")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/shows?movieId=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/shows?date=not-a-date")).andExpect(status().isBadRequest());
        verifyNoInteractions(theaterService, screenService, showService);
    }

    @Test void businessValidationUsesProblemDetails() throws Exception {
        when(showService.create(any())).thenThrow(new BusinessValidationException("startTime must be before endTime"));
        mvc.perform(post("/api/v1/shows").contentType(MediaType.APPLICATION_JSON).content("""
                {"movieId":1,"screenId":2,"theaterId":3,"startTime":"2026-10-01T10:00:00Z",
                 "endTime":"2026-10-01T09:00:00Z","active":true}
                """)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("startTime must be before endTime"));
    }

    @Test void missingResourcesUseProblemDetails() throws Exception {
        when(screenService.getById(9L)).thenThrow(new ResourceNotFoundException("Screen", 9L));
        mvc.perform(get("/api/v1/screens/9")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Screen with id 9 was not found"));
    }
}
