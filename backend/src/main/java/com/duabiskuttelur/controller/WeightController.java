package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.WeightHistoryResponse;
import com.duabiskuttelur.model.WeightRequest;
import com.duabiskuttelur.service.UserService;
import com.duabiskuttelur.service.WeightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weight")
public class WeightController {

    private final WeightService weightService;
    private final UserService userService;

    public WeightController(WeightService weightService, UserService userService) {
        this.weightService = weightService;
        this.userService = userService;
    }

    /** Logs a weigh-in and returns the refreshed weekly-averaged history in one round trip. */
    @PostMapping
    public WeightHistoryResponse logWeight(@RequestBody WeightRequest request) {
        Long userId = userService.currentUser().getId();
        weightService.logWeight(userId, request.weightKg());
        return weightService.history(userId);
    }

    @GetMapping("/history")
    public WeightHistoryResponse history() {
        return weightService.history(userService.currentUser().getId());
    }
}
