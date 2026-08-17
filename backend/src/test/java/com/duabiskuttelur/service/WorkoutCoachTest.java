package com.duabiskuttelur.service;

import com.duabiskuttelur.client.WorkoutCoachClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.WorkoutCoachNote;
import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedSession;
import com.duabiskuttelur.service.WorkoutVocabulary.Energy;
import com.duabiskuttelur.service.WorkoutVocabulary.Feel;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The coach is the only part of the workout feature that talks to a model, and
 * the only part allowed to fail. These are about what happens when it does.
 *
 * <p>The distinction that matters throughout: a rule-based note is a
 * <em>designed</em> outcome, not an error, so it must always be produced and
 * must always be labelled as such. An unlabelled fallback is worse than no
 * fallback, because the UI would present a template as personal coaching.
 */
class WorkoutCoachTest {

    private static WorkoutPlanner planner;

    @BeforeAll
    static void loadTheRealCatalogue() {
        WorkoutCatalog catalog = new WorkoutCatalog();
        catalog.load();
        planner = new WorkoutPlanner(catalog);
    }

    private static final WorkoutProfile PROFILE = new WorkoutProfile(
            Goal.LOSE_WEIGHT, Level.BEGINNER, 3, 30, Set.of(Equipment.NONE), Set.of());

    private static PlannedSession session() {
        return planner.plan(1L, LocalDate.of(2026, 8, 17), PROFILE, List.of());
    }

    private static WorkoutCoach.SessionFacts facts() {
        return new WorkoutCoach.SessionFacts(session(), PROFILE, 2, 3, List.of(Feel.JUST_RIGHT), 0);
    }

    private static AppProperties withKey(boolean present) {
        AppProperties props = Mockito.mock(AppProperties.class);
        when(props.hasGeminiKey()).thenReturn(present);
        return props;
    }

    @Test
    void withNoApiKeyTheModelIsNotCalledAtAll() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);

        WorkoutCoach.CoachedNote note = new WorkoutCoach(client, withKey(false)).noteFor(facts(), "en");

        verify(client, never()).coachNote(anyString(), anyString());
        assertEquals(WorkoutCoach.Source.RULES, note.source());
        assertFalse(note.note().summary().isBlank(), "the fallback produced no note at all");
    }

    @Test
    void aFailedCallStillProducesANoteAndSaysItWasNotTheModel() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.coachNote(anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider is having a moment"));

        WorkoutCoach.CoachedNote note = new WorkoutCoach(client, withKey(true)).noteFor(facts(), "en");

        assertEquals(WorkoutCoach.Source.RULES, note.source());
        assertFalse(note.note().summary().isBlank());
        assertFalse(note.note().factors().isEmpty(),
                "the fallback note had no factors, so \"what did you look at?\" would open empty");
    }

    /**
     * A 200 with nothing in it is the failure mode a try/catch misses. Left
     * unhandled, the dashboard renders an empty coach card that looks like a
     * layout bug rather than a degraded service.
     */
    @Test
    void anEmptyModelAnswerIsTreatedAsAFailure() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.coachNote(anyString(), anyString()))
                .thenReturn(new WorkoutCoachNote("   ", List.of()));

        WorkoutCoach.CoachedNote note = new WorkoutCoach(client, withKey(true)).noteFor(facts(), "en");

        assertEquals(WorkoutCoach.Source.RULES, note.source());
        assertFalse(note.note().summary().isBlank());
    }

    @Test
    void aGoodModelAnswerIsUsedAndLabelledAsSuch() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.coachNote(anyString(), anyString()))
                .thenReturn(new WorkoutCoachNote("Today is lighter on purpose.", List.of("You trained twice")));

        WorkoutCoach.CoachedNote note = new WorkoutCoach(client, withKey(true)).noteFor(facts(), "en");

        assertEquals(WorkoutCoach.Source.AI, note.source());
        assertEquals("Today is lighter on purpose.", note.note().summary());
    }

    /** Every supported language has a fallback; a missing one would be a null note. */
    @Test
    void everySupportedLanguageHasARuleBasedNoteAndReply() {
        WorkoutCoach coach = new WorkoutCoach(Mockito.mock(WorkoutCoachClient.class), withKey(false));

        for (String lang : List.of("en", "ms", "zh")) {
            WorkoutCoach.CoachedNote note = coach.noteFor(facts(), lang);
            assertNotNull(note.note().summary(), lang + " has no rule-based note");
            assertFalse(note.note().summary().isBlank(), lang + " has a blank rule-based note");

            for (Feel feel : Feel.values()) {
                WorkoutCoach.CoachedReply reply = coach.replyFor(session(), feel, Energy.NORMAL, lang);
                assertFalse(reply.reply().isBlank(),
                        lang + " has no rule-based reply for " + feel.tag());
            }
        }
    }

    /** An unknown language must fall back to English, not to a null lookup. */
    @Test
    void anUnknownLanguageFallsBackRatherThanFailing() {
        WorkoutCoach coach = new WorkoutCoach(Mockito.mock(WorkoutCoachClient.class), withKey(false));

        WorkoutCoach.CoachedNote note = coach.noteFor(facts(), "tlh");

        assertFalse(note.note().summary().isBlank());
    }

    /**
     * Skipping the rating must produce silence, not a generic cheer. The
     * completion screen only shows the coach card once something was answered,
     * and a reply to an unanswered question is the app pretending to listen.
     */
    @Test
    void skippingTheRatingProducesNoReplyAndNoModelCall() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);

        WorkoutCoach.CoachedReply reply =
                new WorkoutCoach(client, withKey(true)).replyFor(session(), null, null, "en");

        assertEquals("", reply.reply());
        verify(client, never()).sessionReply(anyString(), anyString());
    }

    @Test
    void aFailedReplyCallStillAnswers() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.sessionReply(anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        WorkoutCoach.CoachedReply reply =
                new WorkoutCoach(client, withKey(true)).replyFor(session(), Feel.TOO_HARD, null, "en");

        assertEquals(WorkoutCoach.Source.RULES, reply.source());
        assertFalse(reply.reply().isBlank());
    }

    /**
     * The context is the model's only source of facts, so anything it is meant
     * to be able to say has to be in there. It must also carry what happens
     * next, or the model is free to promise a change the planner will not make.
     */
    @Test
    void theModelIsHandedTheFactsRatherThanAskedToDeriveThem() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.coachNote(anyString(), anyString()))
                .thenReturn(new WorkoutCoachNote("ok", List.of()));

        WorkoutCoach.SessionFacts facts = new WorkoutCoach.SessionFacts(
                session(), PROFILE, 2, 5, List.of(Feel.TOO_EASY, Feel.TOO_EASY), 1);
        new WorkoutCoach(client, withKey(true)).noteFor(facts, "en");

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).coachNote(context.capture(), anyString());

        String sent = context.getValue();
        assertTrue(sent.contains("lose_weight"), "the goal never reached the model: " + sent);
        assertTrue(sent.contains("3 days a week"), "the training frequency never reached the model");
        assertTrue(sent.contains("last 7 days: 2"), "the recent session count never reached the model");
        assertTrue(sent.contains("5 days"), "the streak never reached the model");
        assertTrue(sent.contains("one more set"),
                "the model was not told the volume went up, so it cannot explain why");
    }

    @Test
    void theReplyContextStatesWhatActuallyHappensNext() {
        WorkoutCoachClient client = Mockito.mock(WorkoutCoachClient.class);
        when(client.sessionReply(anyString(), anyString())).thenReturn("sure");

        new WorkoutCoach(client, withKey(true)).replyFor(session(), Feel.TOO_HARD, Energy.TIRED, "en");

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).sessionReply(context.capture(), anyString());

        assertTrue(context.getValue().contains("drops a set"),
                "the model was not told what the next session does, so its reply could contradict it");
    }
}
