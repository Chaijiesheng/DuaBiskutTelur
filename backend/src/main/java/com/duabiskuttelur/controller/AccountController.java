package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.AchievementsResponse;
import com.duabiskuttelur.model.BudgetRequest;
import com.duabiskuttelur.model.DashboardResponse;
import com.duabiskuttelur.model.MeResponse;
import com.duabiskuttelur.model.ProfileRequest;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.AchievementsService;
import com.duabiskuttelur.service.DashboardService;
import com.duabiskuttelur.service.UserService;
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

    public AccountController(UserService userService, DashboardService dashboardService,
                              AchievementsService achievementsService) {
        this.userService = userService;
        this.dashboardService = dashboardService;
        this.achievementsService = achievementsService;
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
}
