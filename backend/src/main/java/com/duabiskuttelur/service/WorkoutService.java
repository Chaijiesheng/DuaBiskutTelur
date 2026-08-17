package com.duabiskuttelur.service;

import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.model.WorkoutAlternative;
import com.duabiskuttelur.model.WorkoutCoachNote;
import com.duabiskuttelur.model.WorkoutCompleteRequest;
import com.duabiskuttelur.model.WorkoutCompletionResponse;
import com.duabiskuttelur.model.WorkoutGlanceResponse;
import com.duabiskuttelur.model.WorkoutHistoryResponse;
import com.duabiskuttelur.model.WorkoutProfileRequest;
import com.duabiskuttelur.model.WorkoutSessionView;
import com.duabiskuttelur.model.WorkoutStatsResponse;
import com.duabiskuttelur.model.WorkoutTodayResponse;
import com.duabiskuttelur.persistence.WorkoutProfileEntity;
import com.duabiskuttelur.persistence.WorkoutProfileRepository;
import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionEntity.Status;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseEntity;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseRepository;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import com.duabiskuttelur.persistence.WorkoutSetLogEntity;
import com.duabiskuttelur.persistence.WorkoutSetLogRepository;
import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Exercise;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutPlanner.Focus;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedExercise;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedSession;
import com.duabiskuttelur.service.WorkoutVocabulary.Energy;
import com.duabiskuttelur.service.WorkoutVocabulary.Feel;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import com.duabiskuttelur.service.WorkoutVocabulary.Preference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The workout feature's one entry point: onboarding answers in, a day's session
 * and everything that happens to it out.
 *
 * <p>Three rules run through all of it.
 *
 * <p><b>A day's session is written once.</b> {@link #today} returns the stored
 * row or creates it, and {@code UNIQUE (user_id, session_date)} means the second
 * concurrent creator loses and re-reads rather than producing a second plan. A
 * workout you can re-roll by refreshing is not a plan.
 *
 * <p><b>Set logging is idempotent.</b> The client sends the intended state of a
 * set, not an increment, so a queue of offline writes can be replayed blindly.
 *
 * <p><b>The model is optional everywhere.</b> {@link WorkoutCoach} is the only
 * AI in the feature and always answers, from a template if it must.
 */
@Service
public class WorkoutService {

    private static final Logger log = LoggerFactory.getLogger(WorkoutService.class);

    /** Sessions read back for the streak, the week strip and the coach's context. */
    private static final int RECENT_WINDOW_DAYS = 35;

    /**
     * The window the History and Analysis tabs read.
     *
     * <p>Wider than the dashboard's, because those two screens answer questions
     * about the past rather than about today: a 35-day window would silently cap
     * "best streak" and the strength progressions at five weeks, and a personal
     * best that quietly expires is worse than none.
     *
     * <p>Bounded rather than unbounded on purpose — this is a per-user list read
     * on a tab switch, and "everything ever" is a query whose cost grows with
     * how loyal the user is.
     */
    private static final int HISTORY_WINDOW_DAYS = 120;

    /** How many past ratings the planner is allowed to react to. */
    private static final int RECENT_FEELS = 3;

    private final WorkoutProfileRepository profiles;
    private final WorkoutSessionRepository sessions;
    private final WorkoutSessionExerciseRepository exercises;
    private final WorkoutSetLogRepository setLogs;
    private final WorkoutPlanner planner;
    private final WorkoutCatalog catalog;
    private final WorkoutCoach coach;
    private final WeightService weightService;
    private final ObjectMapper mapper;

    private final Map<Focus, Counter> generatedCounters = new EnumMap<>(Focus.class);
    private final Map<WorkoutCoach.Source, Counter> coachSourceCounters =
            new EnumMap<>(WorkoutCoach.Source.class);
    private final Counter completedCounter;

    public WorkoutService(WorkoutProfileRepository profiles, WorkoutSessionRepository sessions,
                          WorkoutSessionExerciseRepository exercises, WorkoutSetLogRepository setLogs,
                          WorkoutPlanner planner, WorkoutCatalog catalog, WorkoutCoach coach,
                          WeightService weightService, ObjectMapper mapper, MeterRegistry meters) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.exercises = exercises;
        this.setLogs = setLogs;
        this.planner = planner;
        this.catalog = catalog;
        this.coach = coach;
        this.weightService = weightService;
        this.mapper = mapper;

        /*
         * Registered here rather than on first use, which is the lesson the
         * usda.match.rejected counters already paid for: Micrometer creates a
         * series the first time it is incremented, so a counter that has never
         * fired is simply absent from /actuator/prometheus. "No workouts have
         * been generated today" and "the workout feature is broken" then look
         * identical on a dashboard. Every value of every tag exists from
         * startup, reading zero.
         */
        for (Focus focus : Focus.values()) {
            generatedCounters.put(focus, Counter.builder(AppMetrics.WORKOUT_SESSION_GENERATED)
                    .description("Workout sessions planned, by what they were built around")
                    .tag(AppMetrics.TAG_FOCUS, focus.tag())
                    .register(meters));
        }
        for (WorkoutCoach.Source source : WorkoutCoach.Source.values()) {
            coachSourceCounters.put(source, Counter.builder(AppMetrics.WORKOUT_COACH_SOURCE)
                    .description("Which path wrote the coaching prose")
                    .tag(AppMetrics.TAG_SOURCE, source.tag())
                    .register(meters));
        }
        completedCounter = Counter.builder(AppMetrics.WORKOUT_SESSION_COMPLETED)
                .description("Workout sessions the user finished")
                .register(meters);
    }

    // ------------------------------------------------------------- profile

    public Optional<WorkoutProfile> profile(long userId) {
        return profiles.findByUserId(userId).map(WorkoutProfile::from);
    }

    /**
     * Stores the six onboarding answers, rejecting anything outside the
     * vocabulary rather than quietly defaulting it.
     *
     * <p>A silently-defaulted answer is the worst outcome available here: the
     * user sees the level they picked on their own screen, the planner uses a
     * different one, and nothing anywhere says they disagree.
     */
    @Transactional
    public WorkoutProfile saveProfile(long userId, WorkoutProfileRequest request) {
        Goal goal = Goal.parse(request.goal())
                .orElseThrow(() -> badRequest("goal", request.goal(), Goal.values()));
        Level level = Level.parse(request.level())
                .orElseThrow(() -> badRequest("level", request.level(), Level.values()));
        int days = require(request.daysPerWeek(), "daysPerWeek", 1, 7);
        int minutes = require(request.sessionMinutes(), "sessionMinutes", 10, 120);
        Set<Equipment> equipment = parseAll(request.equipment(), Equipment::parse, "equipment", Equipment.class);
        Set<Preference> preferences =
                parseAll(request.preferences(), Preference::parse, "preferences", Preference.class);

        WorkoutProfileEntity entity = profiles.findByUserId(userId).orElseGet(() -> {
            WorkoutProfileEntity fresh = new WorkoutProfileEntity();
            fresh.setUserId(userId);
            fresh.setCreatedAt(now());
            return fresh;
        });
        entity.setGoal(goal.tag());
        entity.setLevel(level.tag());
        entity.setDaysPerWeek(days);
        entity.setSessionMinutes(minutes);
        entity.setEquipment(WorkoutProfile.join(equipment));
        entity.setPreferences(WorkoutProfile.join(preferences));
        entity.setUpdatedAt(now());
        profiles.save(entity);

        /*
         * Today's plan was built from the answers that just changed, so it is
         * now describing a user who no longer exists. Dropped rather than
         * regenerated in place: the next read of /today rebuilds it, and doing
         * that lazily keeps the "generate exactly once per day" rule in one
         * method instead of two.
         *
         * A session already under way is left alone. Rewriting the workout
         * somebody is three sets into, because they edited a preference, would
         * be a worse bug than a stale plan.
         */
        sessions.findByUserIdAndSessionDate(userId, today())
                .filter(s -> Status.PLANNED.tag().equals(s.getStatus()))
                .ifPresent(this::deleteSession);

        return WorkoutProfile.from(entity);
    }

    // --------------------------------------------------------------- today

    /** The dashboard payload. Generates today's session if there isn't one yet. */
    @Transactional
    public WorkoutTodayResponse today(long userId, String lang) {
        Optional<WorkoutProfile> maybeProfile = profile(userId);
        if (maybeProfile.isEmpty()) {
            // Before onboarding there is nothing to plan and nothing to say. The
            // client renders the empty state off this flag alone.
            return new WorkoutTodayResponse(false, null, null, null, List.of(), stats(userId, List.of()));
        }
        WorkoutProfile profile = maybeProfile.get();
        LocalDate today = today();
        List<WorkoutSessionEntity> recent = recentSessions(userId, today);
        WorkoutSessionEntity session = sessions.findByUserIdAndSessionDate(userId, today)
                .orElseGet(() -> generate(userId, today, profile, recent, lang));

        return new WorkoutTodayResponse(
                true,
                view(session),
                new WorkoutCoachNote(session.getCoachNote(), coachFactors(session)),
                session.getCoachSource(),
                weekStrip(userId, today, profile, recent),
                stats(userId, recent));
    }

    /**
     * Builds and stores one day's session.
     *
     * <p>The insert can lose a race — two tabs opening the Workout tab at the
     * same moment both find nothing and both plan. The unique constraint decides
     * it, and the loser re-reads rather than retrying, so the user gets one plan
     * and never sees which request built it.
     */
    private WorkoutSessionEntity generate(long userId, LocalDate date, WorkoutProfile profile,
                                          List<WorkoutSessionEntity> recent, String lang) {
        List<Feel> feels = recentFeels(recent);
        PlannedSession planned = planner.plan(userId, date, profile, feels);
        WorkoutCoach.SessionFacts facts = new WorkoutCoach.SessionFacts(
                planned, profile, completedInLastWeek(recent, date), streak(recent, date),
                feels, WorkoutPlanner.volumeAdjustment(feels));
        WorkoutCoach.CoachedNote note = coach.noteFor(facts, lang);

        WorkoutSessionEntity entity = new WorkoutSessionEntity();
        entity.setUserId(userId);
        entity.setSessionDate(date);
        entity.setTitle(planned.title());
        entity.setFocus(planned.focus().tag());
        entity.setMinutes(planned.minutes());
        entity.setLevel(planned.level().tag());
        entity.setStatus(Status.PLANNED.tag());
        entity.setCoachNote(note.note().summary());
        entity.setCoachFactors(serializeFactors(note.note().factors()));
        entity.setCoachSource(note.source().tag());
        entity.setCreatedAt(now());

        WorkoutSessionEntity saved;
        try {
            saved = sessions.save(entity);
        } catch (DataIntegrityViolationException e) {
            log.debug("Lost the race to plan {} for user {}; using the session that won", date, userId);
            return sessions.findByUserIdAndSessionDate(userId, date).orElseThrow(() -> e);
        }
        List<WorkoutSessionExerciseEntity> rows = new ArrayList<>();
        for (int i = 0; i < planned.exercises().size(); i++) {
            rows.add(exerciseRow(saved.getId(), i, planned.exercises().get(i), null));
        }
        exercises.saveAll(rows);

        generatedCounters.get(planned.focus()).increment();
        coachSourceCounters.get(note.source()).increment();
        return saved;
    }

    private WorkoutSessionExerciseEntity exerciseRow(long sessionId, int position,
                                                     PlannedExercise planned, String replacedFrom) {
        WorkoutSessionExerciseEntity row = new WorkoutSessionExerciseEntity();
        row.setSessionId(sessionId);
        row.setPosition(position);
        row.setExerciseKey(planned.exercise().key());
        row.setName(planned.exercise().name());
        row.setTarget(planned.exercise().target());
        row.setSets(planned.sets());
        row.setReps(planned.reps());
        row.setUnit(planned.unit().tag());
        row.setCue(planned.exercise().cue());
        row.setReplacedFrom(replacedFrom);
        return row;
    }

    // ------------------------------------------------------- running a session

    @Transactional
    public WorkoutSessionView start(long userId, long sessionId) {
        WorkoutSessionEntity session = owned(userId, sessionId);
        if (Status.PLANNED.tag().equals(session.getStatus())
                || Status.SKIPPED.tag().equals(session.getStatus())) {
            session.setStatus(Status.IN_PROGRESS.tag());
            session.setStartedAt(now());
            sessions.save(session);
        }
        return view(session);
    }

    /**
     * Marks one set done or not done.
     *
     * <p>Written as "make it so" rather than "add one", which is what lets a
     * client replay its offline queue without tracking what already landed.
     * Logging a set that is already logged is a no-op, and so is clearing one
     * that was never there.
     */
    @Transactional
    public WorkoutSessionView logSet(long userId, long sessionId, int position, int setIndex, boolean done) {
        WorkoutSessionEntity session = owned(userId, sessionId);
        WorkoutSessionExerciseEntity exercise = exercises.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No exercise at position " + position));
        if (setIndex < 0 || setIndex >= exercise.getSets()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Set " + setIndex + " is outside this exercise's " + exercise.getSets() + " sets");
        }

        if (done) {
            if (!setLogs.existsBySessionIdAndExercisePositionAndSetIndex(sessionId, position, setIndex)) {
                WorkoutSetLogEntity row = new WorkoutSetLogEntity();
                row.setSessionId(sessionId);
                row.setExercisePosition(position);
                row.setSetIndex(setIndex);
                row.setCompletedAt(now());
                try {
                    setLogs.save(row);
                } catch (DataIntegrityViolationException e) {
                    // Two flushes of the same queued set, landing together. The
                    // constraint did its job; the set is logged either way.
                    log.debug("Duplicate set log for session {} slot {}/{}", sessionId, position, setIndex);
                }
            }
            if (Status.PLANNED.tag().equals(session.getStatus())) {
                // Logging a set is starting the session, whatever route got here
                // — a resumed session on a second device never hit /start.
                session.setStatus(Status.IN_PROGRESS.tag());
                session.setStartedAt(now());
                sessions.save(session);
            }
        } else {
            setLogs.deleteOne(sessionId, position, setIndex);
        }
        return view(session);
    }

    /** The Replace sheet's options: same movement pattern, no more equipment than the current one. */
    public List<WorkoutAlternative> alternatives(long userId, long sessionId, int position) {
        owned(userId, sessionId);
        WorkoutProfile profile = profile(userId).orElseThrow(WorkoutService::noProfile);
        WorkoutSessionExerciseEntity row = exercises.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No exercise at position " + position));
        Exercise current = catalog.byKey(row.getExerciseKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This session references an exercise no longer in the catalogue"));
        return planner.alternatives(current, profile).stream()
                .map(e -> new WorkoutAlternative(e.key(), e.name(), e.target(), e.cue()))
                .toList();
    }

    /**
     * Swaps one slot for a different exercise.
     *
     * <p>Sets already logged against the slot are cleared. They record work done
     * on a movement that is no longer in the session, and carrying them over
     * would credit push-ups the user never did to the row they swapped to — the
     * position is the same, the exercise is not.
     */
    @Transactional
    public WorkoutSessionView replaceExercise(long userId, long sessionId, int position, String newKey) {
        WorkoutSessionEntity session = owned(userId, sessionId);
        WorkoutProfile profile = profile(userId).orElseThrow(WorkoutService::noProfile);
        WorkoutSessionExerciseEntity row = exercises.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No exercise at position " + position));
        Exercise current = catalog.byKey(row.getExerciseKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This session references an exercise no longer in the catalogue"));
        Exercise replacement = planner.alternatives(current, profile).stream()
                .filter(e -> e.key().equals(newKey))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "'" + newKey + "' is not an offered alternative for this slot"));

        setLogs.deleteBySessionIdAndExercisePosition(sessionId, position);
        row.setReplacedFrom(current.key());
        row.setExerciseKey(replacement.key());
        row.setName(replacement.name());
        row.setTarget(replacement.target());
        row.setUnit(replacement.unit().tag());
        row.setCue(replacement.cue());
        // Sets carry over, reps do not: the slot's place in the session is
        // unchanged, but 12 of one movement is not 12 of another.
        row.setReps(scaleReps(profile.level(), replacement.baseReps()));
        exercises.save(row);
        return view(session);
    }

    private static int scaleReps(Level level, int baseReps) {
        double factor = switch (level) {
            case BEGINNER -> 1.0;
            case INTERMEDIATE -> 1.25;
            case ADVANCED -> 1.5;
        };
        return Math.max(1, (int) Math.round(baseReps * factor));
    }

    @Transactional
    public WorkoutSessionView setSkipped(long userId, long sessionId, boolean skipped) {
        WorkoutSessionEntity session = owned(userId, sessionId);
        if (Status.COMPLETED.tag().equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session is already finished");
        }
        session.setStatus(skipped ? Status.SKIPPED.tag() : Status.PLANNED.tag());
        sessions.save(session);
        return view(session);
    }

    /** Finishes a session and answers the two optional questions on the completion screen. */
    @Transactional
    public WorkoutCompletionResponse complete(long userId, long sessionId, WorkoutCompleteRequest request) {
        WorkoutSessionEntity session = owned(userId, sessionId);
        Feel feel = Feel.parse(request.feel()).orElse(null);
        Energy energy = Energy.parse(request.energy()).orElse(null);
        if (request.feel() != null && !request.feel().isBlank() && feel == null) {
            throw badRequest("feel", request.feel(), Feel.values());
        }
        if (request.energy() != null && !request.energy().isBlank() && energy == null) {
            throw badRequest("energy", request.energy(), Energy.values());
        }

        boolean alreadyDone = Status.COMPLETED.tag().equals(session.getStatus());
        session.setStatus(Status.COMPLETED.tag());
        session.setFeel(feel == null ? null : feel.tag());
        session.setEnergy(energy == null ? null : energy.tag());
        session.setCompletedAt(now());
        session.setActualMinutes(request.actualMinutes() != null && request.actualMinutes() > 0
                ? request.actualMinutes()
                : session.getMinutes());
        sessions.save(session);
        if (!alreadyDone) {
            // Guarded, because the completion screen can be submitted twice —
            // a double tap, or a retry after a flaky response. Counting the
            // same session twice would inflate the one number this feature is
            // ultimately judged on.
            completedCounter.increment();
        }

        List<WorkoutSessionExerciseEntity> rows = exercises.findBySessionIdOrderByPositionAsc(sessionId);
        WorkoutCoach.CoachedReply reply = coach.replyFor(
                asPlanned(session, rows), feel, energy, request.lang());
        coachSourceCounters.get(reply.source()).increment();

        return new WorkoutCompletionResponse(
                view(session),
                session.getActualMinutes(),
                rows.size(),
                setLogs.findBySessionId(sessionId).size(),
                reply.reply(),
                reply.source().tag());
    }

    // ------------------------------------------------- history and analysis

    /**
     * One line about today, for the Today card on the Snap tab.
     *
     * <p>Reads the stored session and stops. It must never plan one: this is the
     * app's home screen, so generating here would fire a Gemini call for the
     * coach note on every user's first open of the day — for a sentence that
     * lives two taps away and that most of them will never see.
     */
    public WorkoutGlanceResponse glance(long userId) {
        Optional<WorkoutProfile> maybeProfile = profile(userId);
        if (maybeProfile.isEmpty()) {
            return new WorkoutGlanceResponse(false, false, null);
        }
        LocalDate today = today();
        boolean trainingDay = isTrainingDay(today, maybeProfile.get());

        return sessions.findByUserIdAndSessionDate(userId, today)
                .map(s -> {
                    List<WorkoutSessionExerciseEntity> rows =
                            exercises.findBySessionIdOrderByPositionAsc(s.getId());
                    return new WorkoutGlanceResponse(true, trainingDay,
                            new WorkoutGlanceResponse.Session(
                                    s.getId(), s.getTitle(), effectiveMinutes(s), s.getStatus(),
                                    setLogs.findBySessionId(s.getId()).size(),
                                    rows.stream().mapToInt(WorkoutSessionExerciseEntity::getSets).sum()));
                })
                .orElseGet(() -> new WorkoutGlanceResponse(true, trainingDay, null));
    }

    /**
     * The Workouts tab inside the History screen.
     *
     * <p>Read-only, and separate from {@link #today} on purpose: that method
     * plans a session when there isn't one, and looking at last week's training
     * must not create this morning's workout as a side effect.
     */
    public WorkoutHistoryResponse history(long userId) {
        LocalDate today = today();
        List<WorkoutSessionEntity> recent = sessionsSince(userId, today, HISTORY_WINDOW_DAYS);
        Map<Long, Integer> logged = setCountsFor(recent);
        Map<Long, Integer> plannedBySession = plannedSetCountsFor(recent);

        List<WorkoutHistoryResponse.Entry> entries = recent.stream()
                .sorted(Comparator.comparing(WorkoutSessionEntity::getSessionDate).reversed())
                .map(s -> new WorkoutHistoryResponse.Entry(
                        s.getId(), s.getSessionDate().toString(), s.getTitle(), s.getFocus(),
                        s.getLevel(), effectiveMinutes(s), s.getStatus(),
                        logged.getOrDefault(s.getId(), 0),
                        plannedBySession.getOrDefault(s.getId(), 0)))
                .toList();

        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Integer> minutesByDate = recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .collect(Collectors.toMap(WorkoutSessionEntity::getSessionDate,
                        WorkoutService::effectiveMinutes, Integer::sum));

        List<WorkoutHistoryResponse.DayMinutes> week = new ArrayList<>();
        int weekMinutes = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            int minutes = minutesByDate.getOrDefault(day, 0);
            weekMinutes += minutes;
            week.add(new WorkoutHistoryResponse.DayMinutes(day.toString(),
                    day.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH), minutes));
        }
        return new WorkoutHistoryResponse(entries, week, weekMinutes);
    }

    /** What a session actually took, falling back to what it was planned to take. */
    private static int effectiveMinutes(WorkoutSessionEntity s) {
        return s.getActualMinutes() != null && s.getActualMinutes() > 0
                ? s.getActualMinutes()
                : s.getMinutes();
    }

    private Map<Long, Integer> setCountsFor(List<WorkoutSessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (WorkoutSessionEntity s : sessions) {
            counts.put(s.getId(), setLogs.findBySessionId(s.getId()).size());
        }
        return counts;
    }

    private Map<Long, Integer> plannedSetCountsFor(List<WorkoutSessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (WorkoutSessionEntity s : sessions) {
            counts.put(s.getId(), exercises.findBySessionIdOrderByPositionAsc(s.getId()).stream()
                    .mapToInt(WorkoutSessionExerciseEntity::getSets).sum());
        }
        return counts;
    }

    /** Workout figures for the Analysis tab. */
    public WorkoutStatsResponse stats(long userId) {
        Optional<WorkoutProfile> maybeProfile = profile(userId);
        LocalDate today = today();
        List<WorkoutSessionEntity> recent = sessionsSince(userId, today, HISTORY_WINDOW_DAYS);
        List<WorkoutSessionEntity> completed = recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .toList();
        List<WorkoutSessionEntity> thisMonth = completed.stream()
                .filter(s -> s.getSessionDate().getMonth() == today.getMonth()
                        && s.getSessionDate().getYear() == today.getYear())
                .toList();

        int expected = maybeProfile.map(p -> expectedSessionsThisMonth(p, today)).orElse(0);
        int consistency = expected == 0 ? 0
                : (int) Math.round(100.0 * Math.min(thisMonth.size(), expected) / expected);

        return new WorkoutStatsResponse(
                maybeProfile.isPresent(),
                thisMonth.size(),
                thisMonth.stream().mapToInt(WorkoutService::effectiveMinutes).sum(),
                consistency,
                expected,
                streak(recent, today),
                bestStreak(completed),
                progressions(completed));
    }

    /**
     * How many sessions the plan called for so far this month.
     *
     * <p>Counted from the rotation rather than from stored rows, which is the
     * only honest version: a session row exists only for a day the user actually
     * opened the tab, so "completed / sessions in the database" would score
     * somebody who never opened the app at 100%. This asks what they said they
     * would do and compares it to what they did.
     *
     * <p>Only days up to today count — being at 50% on the 15th is not the same
     * failure as being at 50% on the 31st, and the tile would read as the latter.
     */
    static int expectedSessionsThisMonth(WorkoutProfile profile, LocalDate today) {
        LocalDate first = today.withDayOfMonth(1);
        int count = 0;
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            if (isTrainingDay(day, profile)) {
                count++;
            }
        }
        return count;
    }

    /** The longest run of consecutive completed days on record. */
    static int bestStreak(List<WorkoutSessionEntity> completed) {
        Set<LocalDate> done = completed.stream()
                .map(WorkoutSessionEntity::getSessionDate)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : done) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            previous = day;
            best = Math.max(best, run);
        }
        return best;
    }

    /**
     * Exercises whose prescribed dose has gone up.
     *
     * <p>Both ends are prescriptions from completed sessions, never a claim the
     * user typed — the catalogue sets the starting dose and the planner raises
     * it, so this is the app showing its own working rather than flattering
     * anybody. Only increases are listed: a dose that dropped after a "too hard"
     * rating is the system working correctly, and putting it under "getting
     * stronger" would read as a rebuke.
     */
    private List<WorkoutStatsResponse.Progression> progressions(List<WorkoutSessionEntity> completed) {
        if (completed.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDate> dateBySession = completed.stream()
                .collect(Collectors.toMap(WorkoutSessionEntity::getId, WorkoutSessionEntity::getSessionDate));
        List<WorkoutSessionExerciseEntity> rows =
                exercises.findBySessionIdIn(List.copyOf(dateBySession.keySet()));

        record Dosed(LocalDate date, WorkoutSessionExerciseEntity row) {}
        Map<String, List<Dosed>> byKey = rows.stream()
                .map(r -> new Dosed(dateBySession.get(r.getSessionId()), r))
                .filter(d -> d.date() != null)
                .collect(Collectors.groupingBy(d -> d.row().getExerciseKey()));

        List<WorkoutStatsResponse.Progression> out = new ArrayList<>();
        byKey.forEach((key, list) -> {
            if (list.size() < 2) {
                return;
            }
            List<Dosed> sorted = list.stream().sorted(Comparator.comparing(Dosed::date)).toList();
            WorkoutSessionExerciseEntity first = sorted.get(0).row();
            WorkoutSessionExerciseEntity last = sorted.get(sorted.size() - 1).row();
            if (volumeOf(last) <= volumeOf(first)) {
                return;
            }
            out.add(new WorkoutStatsResponse.Progression(
                    key, last.getName(), describeDose(first), describeDose(last)));
        });
        out.sort(Comparator.comparing(WorkoutStatsResponse.Progression::name));
        return out;
    }

    private static int volumeOf(WorkoutSessionExerciseEntity row) {
        return row.getSets() * row.getReps();
    }

    /** "3 × 12", or "3 × 30s" where the dose is a duration. */
    private static String describeDose(WorkoutSessionExerciseEntity row) {
        String suffix = "sec".equals(row.getUnit()) ? "s" : "";
        return row.getSets() + " × " + row.getReps() + suffix;
    }

    // ------------------------------------------------------------- reading

    private List<WorkoutSessionEntity> recentSessions(long userId, LocalDate today) {
        return sessionsSince(userId, today, RECENT_WINDOW_DAYS);
    }

    private List<WorkoutSessionEntity> sessionsSince(long userId, LocalDate today, int days) {
        return sessions.findByUserIdAndSessionDateBetweenOrderBySessionDateAsc(
                userId, today.minusDays(days), today);
    }

    /**
     * How the last few completed sessions were rated, newest first.
     *
     * <p>Only completed ones, and only ones that were actually rated: a skipped
     * session says nothing about whether the volume was right, and treating
     * "didn't answer" as a rating would let silence quietly drive the plan.
     */
    private static List<Feel> recentFeels(List<WorkoutSessionEntity> recent) {
        return recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .sorted(Comparator.comparing(WorkoutSessionEntity::getSessionDate).reversed())
                .map(s -> Feel.parse(s.getFeel()))
                .flatMap(Optional::stream)
                .limit(RECENT_FEELS)
                .toList();
    }

    private static int completedInLastWeek(List<WorkoutSessionEntity> recent, LocalDate today) {
        LocalDate from = today.minusDays(6);
        return (int) recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .filter(s -> !s.getSessionDate().isBefore(from))
                .count();
    }

    /**
     * Consecutive days ending today (or yesterday) with a completed session.
     *
     * <p>Yesterday counts as the anchor so a streak does not appear broken all
     * morning before you have trained. Rest days do not extend it — a streak
     * that survives arbitrary gaps is not measuring anything.
     */
    static int streak(List<WorkoutSessionEntity> recent, LocalDate today) {
        Set<LocalDate> done = recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .map(WorkoutSessionEntity::getSessionDate)
                .collect(Collectors.toCollection(HashSet::new));
        LocalDate cursor = done.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (done.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /**
     * The seven columns of the week strip, Monday to Sunday of the current week.
     *
     * <p>"Rest" versus "planned" for a future day is decided here rather than in
     * the client because it depends on the training rotation, which the client
     * does not know and should not have to reimplement to draw a circle.
     */
    private List<WorkoutTodayResponse.WeekDay> weekStrip(long userId, LocalDate today,
                                                         WorkoutProfile profile,
                                                         List<WorkoutSessionEntity> recent) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Map<LocalDate, String> statusByDate = recent.stream()
                .collect(Collectors.toMap(WorkoutSessionEntity::getSessionDate,
                        WorkoutSessionEntity::getStatus, (a, b) -> b));

        List<WorkoutTodayResponse.WeekDay> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            String stored = statusByDate.get(day);
            String state;
            if (Status.COMPLETED.tag().equals(stored)) {
                state = "done";
            } else if (day.equals(today)) {
                state = "today";
            } else if (day.isBefore(today)) {
                // A past day with no completed session is a day that did not
                // happen, whether it was planned, skipped or never generated.
                state = "rest";
            } else {
                state = isTrainingDay(day, profile) ? "planned" : "rest";
            }
            out.add(new WorkoutTodayResponse.WeekDay(day.toString(),
                    day.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH), state));
        }
        return out;
    }

    /**
     * Whether a future day is a training day.
     *
     * <p>Spreads the week's sessions evenly rather than front-loading them:
     * three days a week becomes Monday, Wednesday, Friday, not Monday, Tuesday,
     * Wednesday and a four-day gap.
     */
    static boolean isTrainingDay(LocalDate day, WorkoutProfile profile) {
        int days = Math.max(1, Math.min(7, profile.daysPerWeek()));
        if (days >= 7) {
            return true;
        }
        int index = day.getDayOfWeek().getValue() - 1;
        return (index * days) / 7 != ((index - 1) * days) / 7 || index == 0;
    }

    private WorkoutTodayResponse.Stats stats(long userId, List<WorkoutSessionEntity> recent) {
        LocalDate today = today();
        int thisMonth = (int) recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .filter(s -> s.getSessionDate().getMonth() == today.getMonth()
                        && s.getSessionDate().getYear() == today.getYear())
                .count();
        return new WorkoutTodayResponse.Stats(
                weightService.latestWeightKg(userId).orElse(null), thisMonth, streak(recent, today));
    }

    private WorkoutSessionView view(WorkoutSessionEntity session) {
        List<WorkoutSessionExerciseEntity> rows =
                exercises.findBySessionIdOrderByPositionAsc(session.getId());
        Map<Integer, List<Integer>> doneByPosition = setLogs.findBySessionId(session.getId()).stream()
                .collect(Collectors.groupingBy(WorkoutSetLogEntity::getExercisePosition,
                        Collectors.mapping(WorkoutSetLogEntity::getSetIndex,
                                Collectors.toCollection(ArrayList::new))));
        doneByPosition.values().forEach(java.util.Collections::sort);

        List<WorkoutSessionView.Exercise> views = rows.stream()
                .map(r -> {
                    // The prescription comes from the stored row; the reference
                    // material comes from the catalogue, so improving a warning
                    // or a video link reaches sessions that already happened.
                    // A key that has since left the catalogue simply has neither.
                    Optional<Exercise> catalogued = catalog.byKey(r.getExerciseKey());
                    return new WorkoutSessionView.Exercise(
                            r.getPosition(), r.getExerciseKey(), r.getName(), r.getTarget(),
                            r.getSets(), r.getReps(), r.getUnit(), r.getCue(),
                            doneByPosition.getOrDefault(r.getPosition(), List.of()),
                            catalogued.map(e -> e.pattern().tag()).orElse(null),
                            catalogued.map(Exercise::mistake).orElse(null),
                            catalogued.map(Exercise::videoUrl).orElse(null));
                })
                .toList();

        int totalSets = rows.stream().mapToInt(WorkoutSessionExerciseEntity::getSets).sum();
        int completedSets = doneByPosition.values().stream().mapToInt(List::size).sum();
        // Short, individual muscle groups -- see WorkoutPlanner.summariseTargets
        // for why the catalogue's own longer phrasing cannot go in a chip.
        String targets = WorkoutPlanner.summariseTargets(
                rows.stream().map(WorkoutSessionExerciseEntity::getTarget).toList());

        return new WorkoutSessionView(session.getId(), session.getSessionDate().toString(),
                session.getTitle(), session.getFocus(), session.getMinutes(), session.getLevel(),
                session.getStatus(), targets, totalSets, completedSets, views);
    }

    /**
     * Rebuilds a {@link PlannedSession} from stored rows, so the coach can be
     * given the same shape whether it is reacting to a plan it has just seen or
     * to one persisted days ago.
     *
     * <p>Rows whose exercise has since left the catalogue are dropped. Only the
     * coach reads this, and it is describing what happened rather than what to
     * do, so a missing row costs a sentence a little detail — nothing the user
     * did is lost, because {@link #view} reads the stored copy.
     */
    private PlannedSession asPlanned(WorkoutSessionEntity session, List<WorkoutSessionExerciseEntity> rows) {
        List<PlannedExercise> planned = rows.stream()
                .map(r -> catalog.byKey(r.getExerciseKey())
                        .map(e -> new PlannedExercise(e, r.getSets(), r.getReps())))
                .flatMap(Optional::stream)
                .toList();
        return new PlannedSession(
                Focus.parse(session.getFocus()).orElse(Focus.FULL_BODY),
                session.getTitle(),
                Level.parse(session.getLevel()).orElse(Level.BEGINNER),
                session.getMinutes(),
                planned);
    }

    // ------------------------------------------------------------- deletion

    /**
     * Erasure for one account.
     *
     * <p>The two child tables carry no {@code user_id}, so they cannot be found
     * by the schema sweep that {@code AccountDeletionCompletenessTest} runs —
     * they have to be reached through the sessions being deleted, in that order.
     * Deleting the sessions first would leave rows nothing can name.
     */
    @Transactional
    public int deleteAllForUser(long userId) {
        List<Long> sessionIds = sessions.findIdsByUserId(userId);
        int removed = 0;
        if (!sessionIds.isEmpty()) {
            removed += setLogs.deleteBySessionIdIn(sessionIds);
            removed += exercises.deleteBySessionIdIn(sessionIds);
        }
        removed += sessions.deleteByUserId(userId);
        removed += profiles.deleteByUserId(userId);
        return removed;
    }

    private void deleteSession(WorkoutSessionEntity session) {
        setLogs.deleteBySessionIdIn(List.of(session.getId()));
        exercises.deleteBySessionIdIn(List.of(session.getId()));
        sessions.delete(session);
    }

    // -------------------------------------------------------------- helpers

    /**
     * "Today" in the user's own calendar.
     *
     * <p>The system default zone, not UTC. The container runs with
     * {@code TZ=Asia/Kuala_Lumpur}, and on UTC the day would roll over at 8am
     * local — mid-morning of the day whose workout it is meant to end.
     * {@code WeightService} reads the clock the same way, for the same reason.
     *
     * <p>No injected {@link java.time.Clock}: nothing in this class needs a
     * fixed one, because every date-sensitive decision — the rotation, the
     * streak, the volume adjustment — takes its date as a parameter and is
     * tested directly.
     */
    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.systemDefault());
    }

    private static Instant now() {
        return Instant.now();
    }

    private WorkoutSessionEntity owned(long userId, long sessionId) {
        WorkoutSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session"));
        if (!session.getUserId().equals(userId)) {
            // 404 rather than 403: whether somebody else's session id exists is
            // not this caller's business, and a 403 answers that question.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session");
        }
        return session;
    }

    private static ResponseStatusException noProfile() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Finish workout setup first");
    }

    private static <E extends Enum<E>> ResponseStatusException badRequest(String field, String value, E[] allowed) {
        String options = java.util.Arrays.stream(allowed)
                .map(e -> e.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "'" + value + "' is not a valid " + field + "; expected one of: " + options);
    }

    private static int require(Integer value, String field, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static <E extends Enum<E>> Set<E> parseAll(List<String> raw,
                                                       java.util.function.Function<String, Optional<E>> parse,
                                                       String field, Class<E> type) {
        Set<E> out = EnumSet.noneOf(type);
        if (raw == null) {
            return out;
        }
        for (String value : raw) {
            out.add(parse.apply(value).orElseThrow(
                    () -> badRequest(field, value, type.getEnumConstants())));
        }
        return out;
    }

    // ------------------------------------------------- coach factor storage

    /**
     * The "What did you look at?" bullets, stored as a JSON array beside the
     * summary they belong to.
     *
     * <p>Serialisation failure is swallowed on both sides on purpose. These are
     * the optional disclosure behind a card the user has to tap to open; losing
     * them costs some explanation, and letting a Jackson error take down the
     * whole Workout tab would trade a small loss for a total one. A null or
     * unreadable column reads back as no factors, which is what the client
     * already renders for a session planned before this existed.
     */
    private String serializeFactors(List<String> factors) {
        if (factors == null || factors.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(factors);
        } catch (Exception e) {
            log.warn("Could not store the coach's factors: {}", e.getMessage());
            return null;
        }
    }

    private List<String> coachFactors(WorkoutSessionEntity session) {
        String stored = session.getCoachFactors();
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readerForListOf(String.class).readValue(stored);
        } catch (Exception e) {
            log.warn("Could not read the coach's factors for session {}: {}",
                    session.getId(), e.getMessage());
            return List.of();
        }
    }
}
