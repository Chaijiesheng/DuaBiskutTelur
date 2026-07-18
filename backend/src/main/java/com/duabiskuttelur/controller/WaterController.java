package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.WaterAdjustRequest;
import com.duabiskuttelur.model.WaterTargetRequest;
import com.duabiskuttelur.model.WaterTodayResponse;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.UserService;
import com.duabiskuttelur.service.WaterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/water")
public class WaterController {

    private final WaterService waterService;
    private final UserService userService;

    public WaterController(WaterService waterService, UserService userService) {
        this.waterService = waterService;
        this.userService = userService;
    }

    @GetMapping("/today")
    public WaterTodayResponse today() {
        return waterService.today(userService.currentUser());
    }

    @PostMapping("/adjust")
    public WaterTodayResponse adjust(@RequestBody WaterAdjustRequest request) {
        return waterService.adjust(userService.currentUser(), request.deltaMl());
    }

    @PostMapping("/reset")
    public WaterTodayResponse reset() {
        return waterService.reset(userService.currentUser());
    }

    @PutMapping("/target")
    public WaterTodayResponse setTarget(@RequestBody WaterTargetRequest request) {
        WaterService.validateTarget(request.targetMl());
        UserEntity user = userService.updateWaterTarget(userService.currentUser(), request.targetMl());
        return waterService.today(user);
    }
}
