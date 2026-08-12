package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.BarcodeLookupRequest;
import com.duabiskuttelur.service.BarcodeLookupService;
import com.duabiskuttelur.service.UserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A servings value with no upper bound would let one barcode lookup save an
 * unrealistic multi-thousand-calorie "meal" straight into history, skewing
 * weekly averages. Both endpoints of the valid range are exercised here; the
 * IllegalArgumentException path never touches barcodeLookupService/userService,
 * so nulls are safe for those cases.
 */
class BarcodeControllerTest {

    /** A real EAN-13; the old "123" now fails format validation before servings is even looked at. */
    private static final String CODE = "5000112637922";

    private static BarcodeLookupRequest request(String code, Double servings) {
        return new BarcodeLookupRequest(code, servings, "en");
    }

    @Test
    void rejectsServingsAboveTheSanityCeiling() {
        BarcodeController controller = new BarcodeController(null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request(CODE, 21.0)));
    }

    @Test
    void rejectsNonPositiveServings() {
        BarcodeController controller = new BarcodeController(null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request(CODE, 0.0)));
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request(CODE, -1.0)));
    }

    @Test
    void rejectsServingsBelowTheStepperFloor() {
        BarcodeController controller = new BarcodeController(null, null);
        // The scan screen's stepper bottoms out at half a serving — the API floor matches.
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request(CODE, 0.4)));
    }

    @Test
    void allowsServingsAtTheCeiling() {
        UserService userService = new UserService(null) {
            @Override
            public com.duabiskuttelur.persistence.UserEntity currentUserOrNull() {
                return null;
            }
        };
        BarcodeLookupService lookupService = new BarcodeLookupService(null, null, null, null) {
            @Override
            public com.duabiskuttelur.model.AnalysisResponse lookup(String barcode, double servings,
                                                                      com.duabiskuttelur.persistence.UserEntity user, String lang) {
                return null;
            }
        };
        BarcodeController controller = new BarcodeController(lookupService, userService);
        // Should not throw at either boundary — both the 0.5 floor and the 20 ceiling are inclusive.
        controller.lookup(request(CODE, 0.5));
        controller.lookup(request(CODE, 20.0));
    }

    /** An omitted servings field means one serving, not a rejected request. */
    @Test
    void missingServingsDefaultsToOne() {
        BarcodeLookupService lookupService = new BarcodeLookupService(null, null, null, null) {
            @Override
            public com.duabiskuttelur.model.AnalysisResponse lookup(String barcode, double servings,
                                                                      com.duabiskuttelur.persistence.UserEntity user, String lang) {
                org.junit.jupiter.api.Assertions.assertEquals(1.0, servings);
                return null;
            }
        };
        UserService userService = new UserService(null) {
            @Override
            public com.duabiskuttelur.persistence.UserEntity currentUserOrNull() {
                return null;
            }
        };
        BarcodeController controller = new BarcodeController(lookupService, userService);
        assertDoesNotThrow(() -> controller.lookup(new BarcodeLookupRequest(CODE, null, null)));
    }

    /**
     * Junk in the code used to become an outbound Open Food Facts request on an
     * endpoint reachable without an account — a free way to make the server
     * generate third-party traffic.
     */
    @Test
    void rejectsAnythingThatIsntAPlausibleBarcode() {
        BarcodeController controller = new BarcodeController(null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request("123", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request("not-a-barcode", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request("../../etc/passwd", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> controller.lookup(request(null, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> controller.product("12"));
    }
}
