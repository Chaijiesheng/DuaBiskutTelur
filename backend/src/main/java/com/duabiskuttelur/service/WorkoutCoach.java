package com.duabiskuttelur.service;

import com.duabiskuttelur.client.WorkoutCoachClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.WorkoutCoachNote;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedExercise;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedSession;
import com.duabiskuttelur.service.WorkoutVocabulary.Energy;
import com.duabiskuttelur.service.WorkoutVocabulary.Feel;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the prose around a session — the "Why this workout" card and the reply
 * to how it felt.
 *
 * <p>This is the only part of the workout feature the model touches, and it is
 * deliberately the only part that can fail without costing anything. When there
 * is no API key, or the call fails, or it comes back unparseable, the same facts
 * are assembled into rule-based sentences instead. {@link FeedbackService} makes
 * exactly this trade for meal feedback; the difference here is that the outcome
 * is <em>recorded</em> — every note carries the {@link Source} that produced it,
 * which is what lets the UI say "standard plan" honestly rather than passing off
 * a template as coaching.
 */
@Service
public class WorkoutCoach {

    private static final Logger log = LoggerFactory.getLogger(WorkoutCoach.class);

    /** Which path produced a note. Stored on the session as {@code coach_source}. */
    public enum Source {
        AI, RULES;

        public String tag() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record CoachedNote(WorkoutCoachNote note, Source source) {
    }

    public record CoachedReply(String reply, Source source) {
    }

    /**
     * The facts a note is allowed to be about.
     *
     * <p>Assembled in Java and handed over complete, so the model is never asked
     * to count anything. Every number the coach can mention is a number
     * something else already computed — the same discipline
     * {@code FeedbackService.RemainingBudget} applies to calories.
     */
    public record SessionFacts(PlannedSession session, WorkoutProfile profile,
                               int completedThisWeek, int streakDays,
                               List<Feel> recentFeelsNewestFirst, int volumeAdjustment) {
        public SessionFacts {
            recentFeelsNewestFirst = List.copyOf(recentFeelsNewestFirst);
        }
    }

    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "zh", "ms");
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "en", "English",
            "zh", "Simplified Chinese",
            "ms", "Malay (Bahasa Melayu)");

    private final WorkoutCoachClient client;
    private final AppProperties appProps;

    public WorkoutCoach(WorkoutCoachClient client, AppProperties appProps) {
        this.client = client;
        this.appProps = appProps;
    }

    public CoachedNote noteFor(SessionFacts facts, String lang) {
        String normalized = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        if (appProps.hasGeminiKey()) {
            try {
                WorkoutCoachNote note = client.coachNote(buildNoteContext(facts), LANGUAGE_NAMES.get(normalized));
                if (note != null && note.summary() != null && !note.summary().isBlank()) {
                    return new CoachedNote(note, Source.AI);
                }
                log.warn("Coach note came back empty; using the rule-based note");
            } catch (Exception e) {
                log.warn("Coach note call failed, using the rule-based note: {}", e.getMessage());
            }
        }
        return new CoachedNote(ruleBasedNote(facts, normalized), Source.RULES);
    }

    public CoachedReply replyFor(PlannedSession session, Feel feel, Energy energy, String lang) {
        String normalized = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        if (feel == null) {
            // Nothing was rated, so there is nothing to reply to. The UI only
            // shows this card once a rating is picked, and inventing a reaction
            // to an answer nobody gave is worse than staying quiet.
            return new CoachedReply("", Source.RULES);
        }
        if (appProps.hasGeminiKey()) {
            try {
                String reply = client.sessionReply(
                        buildReplyContext(session, feel, energy), LANGUAGE_NAMES.get(normalized));
                if (reply != null && !reply.isBlank()) {
                    return new CoachedReply(reply, Source.AI);
                }
                log.warn("Coach reply came back empty; using the rule-based reply");
            } catch (Exception e) {
                log.warn("Coach reply call failed, using the rule-based reply: {}", e.getMessage());
            }
        }
        return new CoachedReply(RULE_REPLIES.get(normalized).get(feel), Source.RULES);
    }

    // ------------------------------------------------------------ AI context

    private static String buildNoteContext(SessionFacts facts) {
        WorkoutProfile profile = facts.profile();
        PlannedSession session = facts.session();
        StringBuilder sb = new StringBuilder();
        sb.append("Today's session, already decided:\n");
        sb.append("- Focus: ").append(session.title()).append('\n');
        sb.append("- Planned length: ").append(session.minutes()).append(" minutes\n");
        sb.append("- Level: ").append(session.level().tag()).append('\n');
        sb.append("- Exercises: ").append(session.exercises().stream()
                .map(p -> p.exercise().name() + " " + p.sets() + "x" + p.reps())
                .collect(Collectors.joining(", "))).append('\n');
        sb.append("\nWhat it was built from:\n");
        sb.append("- Their goal: ").append(profile.goal().tag()).append('\n');
        sb.append("- They train ").append(profile.daysPerWeek()).append(" days a week, ")
                .append(profile.sessionMinutes()).append(" minutes a session\n");
        sb.append("- Equipment available: ").append(WorkoutProfile.join(profile.equipment())).append('\n');
        sb.append("- Sessions completed in the last 7 days: ").append(facts.completedThisWeek()).append('\n');
        sb.append("- Current streak: ").append(facts.streakDays()).append(" days\n");
        if (!facts.recentFeelsNewestFirst().isEmpty()) {
            sb.append("- How the last sessions were rated, most recent first: ")
                    .append(facts.recentFeelsNewestFirst().stream().map(Feel::tag)
                            .collect(Collectors.joining(", "))).append('\n');
        }
        if (facts.volumeAdjustment() != 0) {
            sb.append("- Because of those ratings, every exercise today has ")
                    .append(facts.volumeAdjustment() > 0 ? "one more set" : "one fewer set")
                    .append(" than it otherwise would\n");
        }
        return sb.toString();
    }

    private static String buildReplyContext(PlannedSession session, Feel feel, Energy energy) {
        StringBuilder sb = new StringBuilder();
        sb.append("They just finished a ").append(session.minutes()).append("-minute ")
                .append(session.title()).append(" session of ")
                .append(session.exercises().size()).append(" exercises.\n");
        sb.append("They rated it: ").append(feel.tag()).append('\n');
        if (energy != null) {
            sb.append("Their energy right now: ").append(energy.tag()).append('\n');
        }
        sb.append("\nWhat actually happens next, which your reply must match and must not contradict:\n");
        sb.append(switch (feel) {
            case TOO_EASY -> "- If they say it is too easy twice in a row, every exercise gains a set. "
                    + "One easy rating on its own changes nothing yet.\n";
            case JUST_RIGHT -> "- Nothing changes. The next session stays at this volume.\n";
            case TOO_HARD -> "- The next session immediately drops a set from every exercise.\n";
        });
        return sb.toString();
    }

    // ------------------------------------------------------- rule-based path

    /**
     * The note the user gets when there is no model available.
     *
     * <p>Built from the same {@link SessionFacts}, so it says true things rather
     * than encouraging things. It is plainer than the model's, and it should be:
     * the UI labels this state "standard plan", and dressing a template up as
     * personal coaching is how a fallback becomes a lie.
     */
    private static WorkoutCoachNote ruleBasedNote(SessionFacts facts, String lang) {
        Strings s = STRINGS.get(lang);
        PlannedSession session = facts.session();
        WorkoutProfile profile = facts.profile();

        String summary = s.summary.formatted(session.title().toLowerCase(java.util.Locale.ROOT),
                session.minutes(), s.goalPhrase.get(profile.goal()));

        List<String> factors = new ArrayList<>();
        factors.add(s.factorSchedule.formatted(profile.daysPerWeek(), profile.sessionMinutes()));
        factors.add(s.factorVolume.formatted(session.exercises().size(), session.totalSets()));
        if (facts.completedThisWeek() > 0) {
            factors.add(s.factorCompleted.formatted(facts.completedThisWeek()));
        }
        if (facts.volumeAdjustment() > 0) {
            factors.add(s.factorHarder);
        } else if (facts.volumeAdjustment() < 0) {
            factors.add(s.factorEasier);
        }
        return new WorkoutCoachNote(summary, factors);
    }

    /**
     * The translated catalog for the rule-based path.
     *
     * <p>Held here rather than in the frontend's i18n files because these
     * sentences are assembled from facts the server holds — the same reason
     * {@code FeedbackService} keeps its own. The frontend translates labels; this
     * translates statements.
     */
    private record Strings(String summary, Map<Goal, String> goalPhrase,
                           String factorSchedule, String factorVolume, String factorCompleted,
                           String factorHarder, String factorEasier) {
    }

    private static final Map<String, Strings> STRINGS = Map.of(
            "en", new Strings(
                    "Today is a %s session, about %d minutes. It's built around %s.",
                    Map.of(Goal.LOSE_WEIGHT, "steady calorie burn alongside your food logging",
                            Goal.BUILD_MUSCLE, "progressive strength work",
                            Goal.MAINTAIN, "keeping what you've already built",
                            Goal.GENERAL_FITNESS, "a balanced mix of strength and movement"),
                    "You said %d days a week, %d minutes a session",
                    "%d exercises, %d sets in total",
                    "%d session(s) already completed in the last 7 days",
                    "One extra set on everything, because you rated the last two easy",
                    "One fewer set on everything, because the last one was too hard"),
            "ms", new Strings(
                    "Hari ini sesi %s, lebih kurang %d minit. Ia dibina sekitar %s.",
                    Map.of(Goal.LOSE_WEIGHT, "pembakaran kalori yang konsisten bersama catatan makanan anda",
                            Goal.BUILD_MUSCLE, "kerja kekuatan yang meningkat secara berperingkat",
                            Goal.MAINTAIN, "mengekalkan apa yang anda sudah bina",
                            Goal.GENERAL_FITNESS, "campuran seimbang kekuatan dan pergerakan"),
                    "Anda kata %d hari seminggu, %d minit satu sesi",
                    "%d senaman, %d set kesemuanya",
                    "%d sesi sudah siap dalam 7 hari lepas",
                    "Satu set tambahan pada semua, sebab dua sesi lepas anda kata senang",
                    "Satu set kurang pada semua, sebab sesi lepas terlalu sukar"),
            "zh", new Strings(
                    "今天是%s训练，大约 %d 分钟。重点是%s。",
                    Map.of(Goal.LOSE_WEIGHT, "配合你的饮食记录稳定消耗热量",
                            Goal.BUILD_MUSCLE, "循序渐进的力量训练",
                            Goal.MAINTAIN, "保持你已经练出来的水平",
                            Goal.GENERAL_FITNESS, "力量与活动的均衡搭配"),
                    "你选了每周 %d 天，每次 %d 分钟",
                    "共 %d 个动作，%d 组",
                    "过去 7 天已完成 %d 次训练",
                    "每个动作多加一组，因为前两次你都觉得太轻松",
                    "每个动作少一组，因为上一次太吃力"));

    private static final Map<String, Map<Feel, String>> RULE_REPLIES = Map.of(
            "en", Map.of(
                    Feel.TOO_EASY, "Noted. Rate the next one easy too and every exercise gains a set.",
                    Feel.JUST_RIGHT, "Good. We'll hold this level, then nudge the reps up.",
                    Feel.TOO_HARD, "Thanks for saying so. The next session drops a set from everything."),
            "ms", Map.of(
                    Feel.TOO_EASY, "Baik. Kalau sesi seterusnya pun senang, setiap senaman dapat satu set tambahan.",
                    Feel.JUST_RIGHT, "Bagus. Kita kekalkan tahap ini dahulu, kemudian tambah ulangan sedikit.",
                    Feel.TOO_HARD, "Terima kasih beritahu. Sesi seterusnya kurang satu set untuk semua senaman."),
            "zh", Map.of(
                    Feel.TOO_EASY, "知道了。下次也觉得轻松的话，每个动作就多加一组。",
                    Feel.JUST_RIGHT, "很好。先保持这个强度，之后再慢慢加次数。",
                    Feel.TOO_HARD, "谢谢你说出来。下次每个动作都会少一组。"));
}
