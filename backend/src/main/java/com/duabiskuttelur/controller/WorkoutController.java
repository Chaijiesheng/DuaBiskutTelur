package com.duabiskuttelur.controller;

import com.duabiskuttelur.model.WorkoutAlternative;
import com.duabiskuttelur.model.WorkoutCompleteRequest;
import com.duabiskuttelur.model.WorkoutCompletionResponse;
import com.duabiskuttelur.model.WorkoutGlanceResponse;
import com.duabiskuttelur.model.WorkoutHistoryResponse;
import com.duabiskuttelur.model.WorkoutProfileRequest;
import com.duabiskuttelur.model.WorkoutSessionView;
import com.duabiskuttelur.model.WorkoutStatsResponse;
import com.duabiskuttelur.model.WorkoutSetRequest;
import com.duabiskuttelur.model.WorkoutReplaceRequest;
import com.duabiskuttelur.model.WorkoutTodayResponse;
import com.duabiskuttelur.service.UserService;
import com.duabiskuttelur.service.WorkoutService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The Workout tab's API.
 *
 * <p>Every route here is signed-in only, and gets that from
 * {@code SecurityConfig}'s existing {@code .requestMatchers("/api/**")
 * .authenticated()} rather than adding a rule — this feature deliberately opens
 * no new public surface. Meal analysis is anonymous-friendly because a visitor
 * can photograph a plate without an account; a workout plan is built from a
 * training history and a body weight, neither of which a visitor has.
 *
 * <p>Thin on purpose. Validation, vocabulary checks and every ownership decision
 * live in {@link WorkoutService}, so there is one place to read to know what the
 * feature permits.
 */
@RestController
@RequestMapping("/api/workout")
public class WorkoutController {

    private final WorkoutService workoutService;
    private final UserService userService;

    public WorkoutController(WorkoutService workoutService, UserService userService) {
        this.workoutService = workoutService;
        this.userService = userService;
    }

    private long currentUserId() {
        return userService.currentUser().getId();
    }

    /**
     * The whole dashboard, including generating today's session if this is the
     * first look at it today.
     *
     * @param lang which language the coach writes in; the plan itself is
     *             language-independent because it is exercise names from a table
     */
    @GetMapping("/today")
    public WorkoutTodayResponse today(@RequestParam(defaultValue = "en") String lang) {
        return workoutService.today(currentUserId(), lang);
    }

    /**
     * The Workouts tab inside History.
     *
     * <p>Separate from {@code /today} rather than reusing it, because that one
     * <em>plans</em> a session when there isn't one — and opening History to
     * look at last week must not create this morning's workout on the way past.
     */
    @GetMapping("/history")
    public WorkoutHistoryResponse history() {
        return workoutService.history(currentUserId());
    }

    /** Workout figures for the Analysis tab. Also read-only, for the same reason. */
    @GetMapping("/stats")
    public WorkoutStatsResponse stats() {
        return workoutService.stats(currentUserId());
    }

    /**
     * One line for the Today card on the Snap tab.
     *
     * <p>Read-only like the two above, and more strictly so: this is fetched on
     * the app's home screen by everybody, so planning here would mean a session
     * — and a Gemini call — per user per app open.
     */
    @GetMapping("/glance")
    public WorkoutGlanceResponse glance() {
        return workoutService.glance(currentUserId());
    }

    @GetMapping("/profile")
    public WorkoutProfileRequest profile() {
        return workoutService.profile(currentUserId())
                .map(p -> new WorkoutProfileRequest(
                        p.goal().tag(), p.level().tag(), p.daysPerWeek(), p.sessionMinutes(),
                        p.equipment().stream().map(e -> e.tag()).toList(),
                        p.preferences().stream().map(v -> v.tag()).toList()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workout setup hasn't been done yet"));
    }

    /** Saves the six onboarding answers. Also drops today's plan if it was built from the old ones. */
    @PostMapping("/profile")
    public WorkoutTodayResponse saveProfile(@RequestBody WorkoutProfileRequest request,
                                            @RequestParam(defaultValue = "en") String lang) {
        long userId = currentUserId();
        workoutService.saveProfile(userId, request);
        // Returns the dashboard rather than the profile: saving the last answer
        // is immediately followed by "build my plan" on every path through the
        // UI, and a second round trip there is the one the user watches a
        // spinner for.
        return workoutService.today(userId, lang);
    }

    @PostMapping("/sessions/{id}/start")
    public WorkoutSessionView start(@PathVariable long id) {
        return workoutService.start(currentUserId(), id);
    }

    /**
     * Marks one set done or not done.
     *
     * <p>PUT, not POST, and the body states the intended result — this call is
     * idempotent so a client can replay a queue of sets logged offline without
     * tracking which ones already landed.
     */
    @PutMapping("/sessions/{id}/sets")
    public WorkoutSessionView logSet(@PathVariable long id, @RequestBody WorkoutSetRequest request) {
        if (request.exercisePosition() == null || request.setIndex() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "exercisePosition and setIndex are both required");
        }
        return workoutService.logSet(currentUserId(), id, request.exercisePosition(),
                request.setIndex(), Boolean.TRUE.equals(request.done()));
    }

    @GetMapping("/sessions/{id}/exercises/{position}/alternatives")
    public List<WorkoutAlternative> alternatives(@PathVariable long id, @PathVariable int position) {
        return workoutService.alternatives(currentUserId(), id, position);
    }

    @PutMapping("/sessions/{id}/exercises/{position}")
    public WorkoutSessionView replace(@PathVariable long id, @PathVariable int position,
                                      @RequestBody WorkoutReplaceRequest request) {
        return workoutService.replaceExercise(currentUserId(), id, position, request.exerciseKey());
    }

    @PostMapping("/sessions/{id}/skip")
    public WorkoutSessionView skip(@PathVariable long id) {
        return workoutService.setSkipped(currentUserId(), id, true);
    }

    @PostMapping("/sessions/{id}/unskip")
    public WorkoutSessionView unskip(@PathVariable long id) {
        return workoutService.setSkipped(currentUserId(), id, false);
    }

    @PostMapping("/sessions/{id}/complete")
    public WorkoutCompletionResponse complete(@PathVariable long id,
                                              @RequestBody WorkoutCompleteRequest request) {
        return workoutService.complete(currentUserId(), id, request);
    }
}
