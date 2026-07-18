package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.ProviderBusyException;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.service.AnalysisService;
import com.duabiskuttelur.service.PdfReportService;
import com.duabiskuttelur.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final AnalysisService analysisService;
    private final UserService userService;
    private final PdfReportService pdfReportService;
    private final ObjectMapper mapper;

    public AnalysisController(AnalysisService analysisService, UserService userService,
                               PdfReportService pdfReportService, ObjectMapper mapper) {
        this.analysisService = analysisService;
        this.userService = userService;
        this.pdfReportService = pdfReportService;
        this.mapper = mapper;
    }

    @PostMapping("/analyze")
    public AnalysisResponse analyze(@RequestParam("image") MultipartFile image,
                                     @RequestParam(value = "lang", required = false, defaultValue = "en") String lang)
            throws IOException {
        if (image.isEmpty()) {
            throw new IllegalArgumentException("No image uploaded");
        }
        String mediaType = image.getContentType() != null && SUPPORTED_TYPES.contains(image.getContentType())
                ? image.getContentType() : "image/jpeg";
        // Anonymous visitors can analyze too; their result just isn't persisted
        // (user null -> no history row, so nothing survives a refresh).
        var user = userService.currentUserOrNull();
        return analysisService.analyze(image.getBytes(), mediaType, user, lang);
    }

    @GetMapping("/history")
    public List<HistoryEntry> history() {
        return analysisService.history(userService.currentUser().getId());
    }

    @GetMapping("/history/{id}")
    public AnalysisResponse historyDetail(@PathVariable Long id) {
        return analysisService.historyDetail(id, userService.currentUser().getId());
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable Long id) {
        analysisService.deleteEntry(id, userService.currentUser().getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history/{id}/pdf")
    public ResponseEntity<byte[]> historyPdf(@PathVariable Long id) throws IOException {
        // userService.currentUser() throws 401 for visitors — export requires a signed-in account.
        Long userId = userService.currentUser().getId();
        MealAnalysisEntity entity = analysisService.historyEntity(id, userId);
        AnalysisResponse result = mapper.readValue(entity.getResultJson(), AnalysisResponse.class);
        byte[] pdf = pdfReportService.render(result, entity.getCreatedAt());

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("duabiskuttelur-report-" + id + ".pdf")
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @ExceptionHandler(AnalysisService.HistoryEntryNotFoundException.class)
    public ResponseEntity<Map<String, String>> historyEntryNotFound(AnalysisService.HistoryEntryNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", "That report couldn't be found."));
    }

    @ExceptionHandler(AnalysisService.NoFoodDetectedException.class)
    public ResponseEntity<Map<String, String>> noFood(AnalysisService.NoFoodDetectedException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "NO_FOOD_DETECTED",
                "message", "We couldn't spot any food in that photo. Try a clearer shot from above!"));
    }

    @ExceptionHandler(ProviderBusyException.class)
    public ResponseEntity<Map<String, String>> providerBusy(ProviderBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ANALYZER_BUSY",
                "message", "Analyzer is busy, try again in a minute."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", e.getMessage()));
    }

    // Thrown by argument resolution itself when the "image" multipart part is
    // absent entirely (as opposed to present-but-empty, which the isEmpty()
    // check above already catches) — without this it fell through to the
    // catch-all below and reported a client error as a 500 server fault.
    // MultipartException (a request with no multipart/form-data body at all,
    // e.g. `curl -X POST` with nothing attached) is a sibling type, not a
    // subclass — needs its own handler to hit this same 400 path.
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> missingPart(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", "No image uploaded"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("Analysis failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "ANALYSIS_FAILED",
                "message", "Something went wrong while analyzing your meal. Please try again."));
    }
}
