package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.ProviderBusyException;
import com.duabiskuttelur.model.MenuHistoryEntry;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.service.MenuRankingService;
import com.duabiskuttelur.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/menu")
public class MenuController {

    private static final Logger log = LoggerFactory.getLogger(MenuController.class);
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final MenuRankingService menuRankingService;
    private final UserService userService;

    public MenuController(MenuRankingService menuRankingService, UserService userService) {
        this.menuRankingService = menuRankingService;
        this.userService = userService;
    }

    @PostMapping("/rank")
    public MenuRankingResponse rank(@RequestParam("image") MultipartFile image,
                                     @RequestParam(value = "lang", required = false, defaultValue = "en") String lang)
            throws IOException {
        if (image.isEmpty()) {
            throw new IllegalArgumentException("No image uploaded");
        }
        String mediaType = image.getContentType() != null && SUPPORTED_TYPES.contains(image.getContentType())
                ? image.getContentType() : "image/jpeg";
        // Visitors can rank a menu too; the result just isn't persisted, same as /api/analyze.
        var user = userService.currentUserOrNull();
        return menuRankingService.rank(image.getBytes(), mediaType, user, lang);
    }

    @GetMapping("/history")
    public List<MenuHistoryEntry> history() {
        return menuRankingService.history(userService.currentUser().getId());
    }

    @GetMapping("/history/{id}")
    public MenuRankingResponse historyDetail(@PathVariable Long id) {
        return menuRankingService.historyDetail(id, userService.currentUser().getId());
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable Long id) {
        menuRankingService.deleteEntry(id, userService.currentUser().getId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MenuRankingService.HistoryEntryNotFoundException.class)
    public ResponseEntity<Map<String, String>> historyEntryNotFound(MenuRankingService.HistoryEntryNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", "That menu scan couldn't be found."));
    }

    @ExceptionHandler(MenuRankingService.NoDishesDetectedException.class)
    public ResponseEntity<Map<String, String>> noDishes(MenuRankingService.NoDishesDetectedException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "NO_DISHES_DETECTED",
                "message", "We couldn't read any dishes on that menu. Try a clearer, flatter shot with good lighting."));
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

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> missingPart(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", "No image uploaded"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("Menu ranking failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "MENU_RANKING_FAILED",
                "message", "Something went wrong while reading that menu. Please try again."));
    }
}
