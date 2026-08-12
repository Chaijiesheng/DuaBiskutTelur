package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deletion has one ordering requirement that isn't visible from reading the
 * call list, and one coverage requirement that no compiler or foreign key will
 * enforce. Both are here.
 */
class AccountDataServiceTest {

    private static final String GOOGLE_SUB = "google-sub-abc";
    private static final Long USER_ID = 42L;

    private UserRepository userRepository;
    private MealAnalysisRepository mealRepository;
    private MenuScanRepository menuScanRepository;
    private WaterRepository waterRepository;
    private WeightRepository weightRepository;
    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<Session> sessionRepository =
            Mockito.mock(FindByIndexNameSessionRepository.class);

    private AccountDataService service;

    private static UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setGoogleSub(GOOGLE_SUB);
        u.setEmail("someone@example.com");
        return u;
    }

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        mealRepository = Mockito.mock(MealAnalysisRepository.class);
        menuScanRepository = Mockito.mock(MenuScanRepository.class);
        waterRepository = Mockito.mock(WaterRepository.class);
        weightRepository = Mockito.mock(WeightRepository.class);
        Mockito.reset(sessionRepository);
        when(sessionRepository.findByPrincipalName(GOOGLE_SUB)).thenReturn(Map.of());
        service = new AccountDataService(userRepository, mealRepository, menuScanRepository,
                waterRepository, weightRepository, sessionRepository, new ObjectMapper());
    }

    /**
     * Every user_id column, with no foreign key behind any of them — so nothing
     * cascades and nothing fails if a table is forgotten. A table left out here
     * just quietly outlives the account it belonged to, which is precisely the
     * failure a deletion feature must not have.
     */
    @Test
    void deletionClearsEveryTableKeyedToTheUser() {
        UserEntity user = user();

        service.deleteAccount(user);

        verify(mealRepository).deleteByUserId(USER_ID);
        verify(menuScanRepository).deleteByUserId(USER_ID);
        verify(waterRepository).deleteByUserId(USER_ID);
        verify(weightRepository).deleteByUserId(USER_ID);
        verify(userRepository).delete(user);
    }

    /**
     * Sessions must be revoked before the rows go, not after. Another device
     * holding a live session keeps authenticating throughout the delete, and
     * UserService.currentUserOrNull() recreates a missing user row from the
     * session's own OAuth attributes — so a request landing in the gap between
     * "rows deleted" and "sessions revoked" silently resurrects the account.
     */
    @Test
    void sessionsAreRevokedBeforeAnyDataIsDeleted() {
        when(sessionRepository.findByPrincipalName(GOOGLE_SUB))
                .thenReturn(Map.of("session-on-phone", Mockito.mock(Session.class),
                        "session-on-laptop", Mockito.mock(Session.class)));

        service.deleteAccount(user());

        InOrder order = inOrder(sessionRepository, mealRepository, userRepository);
        order.verify(sessionRepository).findByPrincipalName(GOOGLE_SUB);
        order.verify(mealRepository).deleteByUserId(USER_ID);
        order.verify(userRepository).delete(Mockito.any());

        verify(sessionRepository).deleteById("session-on-phone");
        verify(sessionRepository).deleteById("session-on-laptop");
    }

    /** Deletion is scoped to one user; a bulk wipe would take everyone else's history with it. */
    @Test
    void deletionNeverFallsBackToAWholeTableWipe() {
        service.deleteAccount(user());

        verify(mealRepository).deleteByUserId(USER_ID);
        verify(mealRepository, never()).deleteAll();
        verify(menuScanRepository, never()).deleteAll();
        verify(waterRepository, never()).deleteAll();
        verify(weightRepository, never()).deleteAll();
        verify(userRepository, never()).deleteAll();
    }
}
