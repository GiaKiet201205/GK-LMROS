package vn.gk.logistics.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorsTest {
    @RestController static class BrokenController {
        @GetMapping("/broken") Object fail(){throw new IllegalStateException("password=DO_NOT_EXPOSE SQL host=private-db");}
        @GetMapping("/conflict") Object conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Phiếu đã xử lý");}
        @GetMapping("/number") Object number(@RequestParam int id){return id;}
    }
    @Test void unhandledFailuresReturnSafeJsonWithSupportId() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new BrokenController()).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/broken")).andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.message").value("Có lỗi hệ thống. Vui lòng thử lại hoặc liên hệ hỗ trợ."))
            .andExpect(jsonPath("$.requestId").isString()).andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("DO_NOT_EXPOSE"))));
    }
    @Test void specificErrorsKeepTheirStatusAndMessage() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new BrokenController()).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Phiếu đã xử lý"));
        mvc.perform(get("/number").param("id","bad")).andExpect(status().isBadRequest());
        mvc.perform(post("/broken")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/unknown")).andExpect(status().isNotFound());
    }
}
