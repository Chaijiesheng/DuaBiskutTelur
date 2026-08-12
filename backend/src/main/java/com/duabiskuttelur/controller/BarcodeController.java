package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.BarcodeLookupRequest;
import com.duabiskuttelur.service.BarcodeLookupService;
import com.duabiskuttelur.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class BarcodeController {

    private static final Logger log = LoggerFactory.getLogger(BarcodeController.class);
    // A real order might be a few packages, not a pallet — this is a sanity
    // ceiling against fat-fingered or garbage input, not a realistic serving size.
    // The floor matches the scan screen's stepper minimum (half a serving).
    private static final double MIN_SERVINGS = 0.5;
    private static final double MAX_SERVINGS = 20;
    private static final double DEFAULT_SERVINGS = 1;
    private static final String DEFAULT_LANG = "en";
    // EAN-8 through GTIN-14 covers every symbology the scanner emits. Checked
    // here so obvious junk costs a 400 instead of an outbound Open Food Facts
    // call on an endpoint anyone can reach without an account.
    private static final Pattern BARCODE = Pattern.compile("[0-9]{8,14}");

    private final BarcodeLookupService barcodeLookupService;
    private final UserService userService;

    public BarcodeController(BarcodeLookupService barcodeLookupService, UserService userService) {
        this.barcodeLookupService = barcodeLookupService;
        this.userService = userService;
    }

    // Resolves just the product name/unit basis, with no scoring or history
    // write — the confirm screen calls this first so it can show what was
    // actually scanned (and the correct serving unit) before the user commits
    // to a quantity, instead of asking "how many?" over raw barcode digits.
    @GetMapping("/barcode/{code}/product")
    public BarcodeLookupService.ProductInfo product(@PathVariable String code) {
        return barcodeLookupService.lookupProduct(validCode(code));
    }

    /**
     * POST, not GET, because this writes a meal into history.
     *
     * <p>It was a GET, which made it reachable by a cross-site top-level
     * navigation: SameSite=Lax deliberately sends the session cookie on those,
     * and CSRF tokens are disabled app-wide on the strength of that cookie, so
     * any page could redirect a signed-in visitor at this URL and silently log
     * meals to their history — and burn an Open Food Facts call doing it. Link
     * prefetchers and chat-app unfurlers could trigger the same thing without
     * anyone intending to. A POST body can't be produced by a navigation, and
     * a cross-origin fetch would need CORS approval this app doesn't grant.
     */
    @PostMapping("/barcode/lookup")
    public AnalysisResponse lookup(@RequestBody BarcodeLookupRequest request) {
        String code = validCode(request.code());
        double servings = request.servings() == null ? DEFAULT_SERVINGS : request.servings();
        if (servings < MIN_SERVINGS || servings > MAX_SERVINGS) {
            throw new IllegalArgumentException(
                    "servings must be between " + MIN_SERVINGS + " and " + (int) MAX_SERVINGS);
        }
        String lang = request.lang() == null || request.lang().isBlank() ? DEFAULT_LANG : request.lang();
        // Anonymous visitors can scan too; their result just isn't persisted, same as the photo flow.
        var user = userService.currentUserOrNull();
        return barcodeLookupService.lookup(code, servings, user, lang);
    }

    private static String validCode(String code) {
        if (code == null || !BARCODE.matcher(code).matches()) {
            throw new IllegalArgumentException("barcode must be 8 to 14 digits");
        }
        return code;
    }

    @ExceptionHandler(BarcodeLookupService.BarcodeNotFoundException.class)
    public ResponseEntity<Map<String, String>> barcodeNotFound(BarcodeLookupService.BarcodeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "BARCODE_NOT_FOUND",
                "message", "Couldn't find that product."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("Barcode lookup failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "BARCODE_LOOKUP_FAILED",
                "message", "Something went wrong while looking up that product. Please try again."));
    }
}
