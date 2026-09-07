package com.tarikusta.spacesurvivors.leaderboard;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.domain.RuleViolationException;
import com.tarikusta.spacesurvivors.player.PlayerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rules of the leaderboard, with no database and no HTTP in sight.
 *
 * <p>This is the payoff of keeping {@link LeaderboardService} free of both: the whole
 * class is exercised in milliseconds against two stubs.</p>
 */
class LeaderboardServiceTest {

    private static final Caller CALLER = new Caller("dev-test", "127.0.0.1");
    private static final UUID PLAYER = UUID.randomUUID();

    private LeaderboardRepository board;
    private PlayerService players;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        board = Mockito.mock(LeaderboardRepository.class);
        players = Mockito.mock(PlayerService.class);
        service = new LeaderboardService(board, players);

        when(players.resolveOrCreate(any())).thenReturn(PLAYER);
    }

    private static LeaderboardDtos.Submission run(String mode, double seconds, int kills) {
        return new LeaderboardDtos.Submission(mode, seconds, kills, 10, 1);
    }

    @Nested
    @DisplayName("only a better run is written")
    class PersonalBest {

        @Test
        void writesWhenThereIsNoPreviousEntry() {
            when(board.personalBest(PLAYER, "infinite")).thenReturn(Optional.empty());
            when(board.findMe(PLAYER, "infinite")).thenReturn(Optional.of(new LeaderboardDtos.Me(1, 300)));

            LeaderboardDtos.SubmitResult result = service.submit(CALLER, run("infinite", 300, 100));

            assertThat(result.isNewRecord()).isTrue();
            verify(board).saveBest(PLAYER, "infinite", 300, 100, 10, 1);
        }

        @Test
        void writesWhenTheRunBeatsTheStoredBest() {
            when(board.personalBest(PLAYER, "infinite")).thenReturn(Optional.of(250.0));
            when(board.findMe(PLAYER, "infinite")).thenReturn(Optional.of(new LeaderboardDtos.Me(1, 300)));

            assertThat(service.submit(CALLER, run("infinite", 300, 100)).isNewRecord()).isTrue();
            verify(board).saveBest(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt());
        }

        @Test
        void doesNotWriteWhenTheRunIsWorse() {
            when(board.personalBest(PLAYER, "infinite")).thenReturn(Optional.of(300.0));
            when(board.findMe(PLAYER, "infinite")).thenReturn(Optional.of(new LeaderboardDtos.Me(2, 300)));

            LeaderboardDtos.SubmitResult result = service.submit(CALLER, run("infinite", 250, 90));

            assertThat(result.isNewRecord()).isFalse();
            assertThat(result.personalBest()).isEqualTo(300);
            verify(board, never()).saveBest(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt());
        }

        @Test
        void doesNotWriteWhenTheRunOnlyEqualsTheStoredBest() {
            when(board.personalBest(PLAYER, "infinite")).thenReturn(Optional.of(300.0));
            when(board.findMe(PLAYER, "infinite")).thenReturn(Optional.of(new LeaderboardDtos.Me(1, 300)));

            assertThat(service.submit(CALLER, run("infinite", 300, 100)).isNewRecord()).isFalse();
            verify(board, never()).saveBest(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("mode is normalised, then whitelisted")
    class Modes {

        @ParameterizedTest
        @ValueSource(strings = { "infinite", "INFINITE", "  Infinite  ", "campaign" })
        void acceptsTheGamesModesInAnyCasing(String mode) {
            when(board.personalBest(any(), anyString())).thenReturn(Optional.empty());
            when(board.findMe(any(), anyString())).thenReturn(Optional.of(new LeaderboardDtos.Me(1, 10)));

            assertThat(service.submit(CALLER, run(mode, 10, 1))).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = { "sandbox", "", "   ", "infinite2" })
        void rejectsAnythingElse(String mode) {
            assertThatThrownBy(() -> service.submit(CALLER, run(mode, 10, 1)))
                    .isInstanceOf(RuleViolationException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> service.submit(CALLER, run(null, 10, 1)))
                    .isInstanceOf(RuleViolationException.class);
        }
    }

    @Nested
    @DisplayName("implausible runs are refused")
    class Plausibility {

        @Test
        void rejectsAKillRateTheGameCannotProduce() {
            assertThatThrownBy(() -> service.submit(CALLER, run("infinite", 10, 50_000)))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessageContaining("kill rate");
            verify(board, never()).saveBest(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt());
        }

        @Test
        void doesNotJudgeTheRateOnVeryShortRuns() {
            // The opening spawn burst makes the ratio meaningless below the threshold,
            // so a short run with a high count must still be accepted.
            when(board.personalBest(any(), anyString())).thenReturn(Optional.empty());
            when(board.findMe(any(), anyString())).thenReturn(Optional.of(new LeaderboardDtos.Me(1, 2)));

            assertThat(service.submit(CALLER, run("infinite", 2, 500))).isNotNull();
        }
    }

    @Nested
    @DisplayName("the board")
    class Board {

        @Test
        void numbersRowsByPositionAndIncludesTheCaller() {
            when(board.top("infinite", 100)).thenReturn(List.of(
                    new LeaderboardRepository.BoardRow("Ada", 600, 900, 30),
                    new LeaderboardRepository.BoardRow("Kaptan", 300, 420, 18)));
            when(board.findMe(PLAYER, "infinite")).thenReturn(Optional.of(new LeaderboardDtos.Me(2, 300)));

            LeaderboardDtos.Board result = service.board(CALLER, "infinite", 100);

            assertThat(result.entries()).extracting(LeaderboardDtos.BoardEntry::rank).containsExactly(1, 2);
            assertThat(result.entries()).extracting(LeaderboardDtos.BoardEntry::displayName)
                    .containsExactly("Ada", "Kaptan");
            assertThat(result.me().rank()).isEqualTo(2);
        }

        @Test
        void leavesMeNullWhenTheCallerHasNoEntry() {
            when(board.top("campaign", 100)).thenReturn(List.of());
            when(board.findMe(PLAYER, "campaign")).thenReturn(Optional.empty());

            assertThat(service.board(CALLER, "campaign", 100).me()).isNull();
        }

        @ParameterizedTest
        @ValueSource(ints = { -5, 0, 1, 100, 200, 5000 })
        void clampsTheRequestedSizeIntoRange(int requested) {
            when(board.top(anyString(), anyInt())).thenReturn(List.of());
            when(board.findMe(any(), anyString())).thenReturn(Optional.empty());

            service.board(CALLER, "infinite", requested);

            int expected = Math.clamp(requested, 1, 200);
            verify(board).top("infinite", expected);
        }
    }
}
