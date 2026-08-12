package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.AccountExport;
import com.duabiskuttelur.model.AchievementsResponse;
import com.duabiskuttelur.model.BudgetRequest;
import com.duabiskuttelur.model.DashboardResponse;
import com.duabiskuttelur.model.MeResponse;
import com.duabiskuttelur.model.ProfileRequest;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.AccountDataService;
import com.duabiskuttelur.service.AchievementsService;
import com.duabiskuttelur.service.DashboardService;
import com.duabiskuttelur.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final UserService userService;
    private final DashboardService dashboardService;
    private final AchievementsService achievementsService;
    private final AccountDataService accountDataService;

    public AccountController(UserService userService, DashboardService dashboardService,
                              AchievementsService achievementsService,
                              AccountDataService accountDataService) {
        this.userService = userService;
        this.dashboardService = dashboardService;
        this.achievementsService = achievementsService;
        this.accountDataService = accountDataService;
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.from(userService.currentUser());
    }

    @GetMapping("/dashboard/today")
    public DashboardResponse dashboardToday() {
        return dashboardService.today(userService.currentUser());
    }

    @GetMapping("/achievements")
    public AchievementsResponse achievements(
            @RequestParam(value = "lang", required = false, defaultValue = "en") String lang) {
        return achievementsService.forUser(userService.currentUser().getId(), lang);
    }

    @PostMapping("/profile")
    public MeResponse saveProfile(@RequestBody ProfileRequest r) {
        UserEntity user = userService.currentUser();
        return MeResponse.from(userService.updateProfile(
                user, r.age(), r.sex(), r.weightKg(), r.heightCm(), r.steps(), r.exerciseFrequency(), r.goal()));
    }

    @PutMapping("/budget")
    public MeResponse saveBudget(@RequestBody BudgetRequest r) {
        UserEntity user = userService.currentUser();
        return MeResponse.from(userService.updateBudget(user, r.dailyBudget()));
    }

    /**
     * The user's full record as a downloadable JSON file. Uncapped on purpose —
     * the 50-row limit on the history list is a UI concern, and an export that
     * quietly omitted the oldest meals wouldn't be the copy of their data the
     * user asked for.
     */
    @GetMapping("/account/export")
    public ResponseEntity<AccountExport> exportAccountData() {
        AccountExport export = accountDataService.export(userService.currentUser());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("duabiskuttelur-data-export.json")
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(export);
    }

    /**
     * Irreversible: erases the account and everything keyed to it. DELETE rather
     * than POST so it can't be reached by a cross-site navigation — forms and
     * top-level navigations can only issue GET or POST, which is what makes the
     * SameSite=Lax cookie sufficient protection here without a CSRF token.
     */
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(HttpServletRequest request) {
        accountDataService.deleteAccount(userService.currentUser());

        // This request's own session outlives the sweep inside deleteAccount
        // (it is loaded for the request, not re-read per call), so it is closed
        // out here — after the data is gone, never before.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
