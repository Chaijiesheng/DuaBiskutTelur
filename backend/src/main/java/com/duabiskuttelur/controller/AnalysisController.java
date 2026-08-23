package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.ProviderBusyException;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.model.PortionCorrectionRequest;
import com.duabiskuttelur.model.RecentMealPoint;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.service.AnalysisService;
import com.duabiskuttelur.service.PdfReportService;
import com.duabiskuttelur.service.PortionCorrectionService;
import com.duabiskuttelur.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.duabiskuttelur.service.TrendPdfService;
import com.duabiskuttelur.service.TrendReportService;
import java.time.LocalDate;
import java.time.ZoneId;
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
    /** A trend window, not an export — the whole point is that it stays small enough to be uncapped. */
    private static final int MAX_RECENT_DAYS = 90;

    private final AnalysisService analysisService;
    private final UserService userService;
    private final TrendReportService trendReportService;
    private final TrendPdfService trendPdfService;
    private final PdfReportService pdfReportService;
    private final PortionCorrectionService portionCorrectionService;
    private final ObjectMapper mapper;

    public AnalysisController(AnalysisService analysisService, UserService userService,
                               PdfReportService pdfReportService,
                               PortionCorrectionService portionCorrectionService,
                               TrendReportService trendReportService,
                               TrendPdfService trendPdfService, ObjectMapper mapper) {
        this.analysisService = analysisService;
        this.userService = userService;
        this.pdfReportService = pdfReportService;
        this.portionCorrectionService = portionCorrectionService;
        this.trendReportService = trendReportService;
        this.trendPdfService = trendPdfService;
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

    /**
     * The weekly or monthly trend report.
     *
     * <p>Signed-in only, like every per-user read: the report is a comparison
     * across days, and a visitor has no days to compare.
     */
    @GetMapping("/trends")
    public TrendReportResponse trends(
            @RequestParam(value = "period", required = false, defaultValue = "week") String period,
            @RequestParam(value = "lang", required = false, defaultValue = "en") String lang) {
        return trendReportService.report(
                userService.currentUser(),
                TrendReportService.Period.parse(period),
                LocalDate.now(ZoneId.systemDefault()),
                lang);
    }

    /**
     * The trend report as a PDF, for taking somewhere the app is not.
     *
     * <p>Rendered in English regardless of the user's language: the base-14 PDF
     * fonts cannot encode CJK, and a document that silently drops half its text
     * is worse than one in a second language. The narrative is requested in
     * English to match, rather than embedding a paragraph the page cannot draw.
     */
    @GetMapping("/trends/pdf")
    public ResponseEntity<byte[]> trendsPdf(
            @RequestParam(value = "period", required = false, defaultValue = "week") String period) {
        TrendReportResponse report = trendReportService.report(
                userService.currentUser(),
                TrendReportService.Period.parse(period),
                LocalDate.now(ZoneId.systemDefault()),
                "en");
        byte[] pdf = trendPdfService.render(report);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(TrendPdfService.filenameFor(report))
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/history")
    public List<HistoryEntry> history() {
        return analysisService.history(userService.currentUser().getId());
    }

    /**
     * The trailing window used by the weekly chart and the Analysis tab, which
     * previously derived both from the fifty-row history list and undercounted
     * for anyone logging more than a handful of meals a day.
     */
    @GetMapping("/history/recent")
    public List<RecentMealPoint> recentHistory(
            @RequestParam(value = "days", required = false, defaultValue = "7") int days) {
        if (days < 1 || days > MAX_RECENT_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_RECENT_DAYS);
        }
        return analysisService.recentPoints(userService.currentUser().getId(), days);
    }

    @GetMapping("/history/{id}")
    public AnalysisResponse historyDetail(@PathVariable Long id) {
        return analysisService.historyDetail(id, userService.currentUser().getId());
    }

    /**
     * "That was half that much." Re-grades a saved meal against corrected
     * portions — the deterministic engine only, no model call, so it is instant
     * and free. PUT because it is idempotent: the multipliers are absolute, so
     * sending the same body twice leaves the same meal.
     */
    @PutMapping("/history/{id}/portions")
    public AnalysisResponse correctPortions(
            @PathVariable Long id,
            @Valid @RequestBody PortionCorrectionRequest request,
            @RequestParam(value = "lang", required = false, defaultValue = "en") String lang) {
        return portionCorrectionService.correct(
                id, userService.currentUser(), request.multipliers(), lang);
    }

    /**
     * "That isn't in the photo." Drops one misidentified item and returns the
     * meal re-graded — same deterministic path as a portion correction, no model
     * call. DELETE on the item rather than a flag on the portions body: removing
     * a food is a different claim from resizing one, and folding it in would
     * make the multiplier list carry a second meaning by position.
     */
    @DeleteMapping("/history/{id}/foods/{index}")
    public AnalysisResponse removeFood(
            @PathVariable Long id,
            @PathVariable int index,
            @RequestParam(value = "lang", required = false, defaultValue = "en") String lang) {
        return portionCorrectionService.removeFood(id, userService.currentUser(), index, lang);
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

    /**
     * 409 rather than 400: the request is well-formed and the user is not
     * confused — they are saying the whole entry is wrong. The distinct code
     * lets the client offer to delete the meal instead of just refusing.
     */
    @ExceptionHandler(PortionCorrectionService.LastFoodException.class)
    public ResponseEntity<Map<String, String>> lastFood(PortionCorrectionService.LastFoodException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "LAST_FOOD",
                "message", e.getMessage()));
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

    // Bean Validation failures on a @RequestBody arrive as this, which is an
    // Exception like any other — so without a handler of its own it fell into
    // the catch-all below and a rejected portion multiplier was reported to the
    // client as a 500 server fault. Same shape of bug as the multipart one above.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validationFailed(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", field != null && field.getDefaultMessage() != null
                        ? field.getDefaultMessage() : "That request wasn't valid."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("Analysis failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "ANALYSIS_FAILED",
                "message", "Something went wrong while analyzing your meal. Please try again."));
    }
}
