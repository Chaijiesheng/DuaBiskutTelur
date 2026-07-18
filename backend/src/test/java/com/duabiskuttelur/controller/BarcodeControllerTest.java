package com.duabiskuttelur.controller;

import com.duabiskuttelur.service.BarcodeLookupService;
import com.duabiskuttelur.service.UserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A servings value with no upper bound would let one barcode lookup save an
 * unrealistic multi-thousand-calorie "meal" straight into history, skewing
 * weekly averages. Both endpoints of the valid range are exercised here; the
 * IllegalArgumentException path never touches barcodeLookupService/userService,
 * so nulls are safe for those cases.
 */
class BarcodeControllerTest {

    @Test
    void rejectsServingsAboveTheSanityCeiling() {
        BarcodeController controller = new BarcodeController(null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.lookup("123", 21, "en"));
    }

    @Test
    void rejectsNonPositiveServings() {
        BarcodeController controller = new BarcodeController(null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.lookup("123", 0, "en"));
        assertThrows(IllegalArgumentException.class, () -> controller.lookup("123", -1, "en"));
    }

    @Test
    void rejectsServingsBelowTheStepperFloor() {
        BarcodeController controller = new BarcodeController(null, null);
        // The scan screen's stepper bottoms out at half a serving — the API floor matches.
        assertThrows(IllegalArgumentException.class, () -> controller.lookup("123", 0.4, "en"));
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
        controller.lookup("123", 0.5, "en");
        controller.lookup("123", 20, "en");
    }
}
