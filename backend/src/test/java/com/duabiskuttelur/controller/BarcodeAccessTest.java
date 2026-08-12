package com.duabiskuttelur.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The barcode routes are the only unauthenticated ones enumerated by method and
 * path rather than by prefix, and getting either matcher wrong fails quietly in
 * opposite directions: too narrow and the scanner 401s for the visitors it is
 * meant to serve, too broad and a future route under the same prefix is exposed
 * without anyone deciding to. Neither shows up in a unit test of the controller.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:barcode-access-test;DB_CLOSE_DELAY=-1",
        // Open Food Facts pointed at a closed port. These assertions are about
        // who may call the endpoint, not about what a real product returns, and
        // without this the suite reaches the live API — a genuine barcode came
        // back 200 from the internet on the first run of this test.
        // OpenFoodFactsClient swallows the connection failure and reports the
        // product missing, so "not 401" is still exactly what is being proven.
        "app.open-food-facts-base-url=http://127.0.0.1:1",
        "app.connect-timeout-ms=250"
})
@AutoConfigureMockMvc
class BarcodeAccessTest {

    private static final String CODE = "5000112637922";

    @Autowired private MockMvc mvc;

    /** Visitors can scan without an account — that's the whole point of the flow. */
    @Test
    void theCommittingLookupIsReachableWithoutAnAccount() throws Exception {
        mvc.perform(post("/api/barcode/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\",\"servings\":1,\"lang\":\"en\"}"))
                // Not 401: it gets as far as the (unreachable, in this test) Open
                // Food Facts call and reports the product missing.
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BARCODE_NOT_FOUND"));
    }

    @Test
    void theProductPreviewIsAlsoReachableWithoutAnAccount() throws Exception {
        mvc.perform(get("/api/barcode/" + CODE + "/product"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BARCODE_NOT_FOUND"));
    }

    /**
     * The write used to live here as a GET, which a cross-site navigation could
     * trigger with the session cookie attached. It must not answer any more.
     */
    @Test
    void theOldGetThatWroteToHistoryIsGone() throws Exception {
        mvc.perform(get("/api/barcode/" + CODE + "?servings=20"))
                .andExpect(result -> {
                    int actual = result.getResponse().getStatus();
                    if (actual == 200) {
                        throw new AssertionError("GET /api/barcode/{code} still serves the history-writing lookup");
                    }
                });
    }

    /** Nothing else under the prefix is anonymous — the matchers name two routes, not a subtree. */
    @Test
    void otherBarcodeRoutesStillRequireAuthentication() throws Exception {
        mvc.perform(post("/api/barcode/anything-else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedCodeIsRejectedBeforeAnyOutboundCall() throws Exception {
        mvc.perform(post("/api/barcode/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"not-a-barcode\",\"servings\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
