package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.domain.AlreadyTakenException;
import com.tarikusta.spacesurvivors.domain.InvalidInputException;
import com.tarikusta.spacesurvivors.domain.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServiceTest {

    private static final Caller CALLER = new Caller("dev-abc", "127.0.0.1");
    private static final UUID EXISTING = UUID.randomUUID();
    private static final UUID CREATED = UUID.randomUUID();

    private PlayerRepository players;
    private PlayerService service;

    @BeforeEach
    void setUp() {
        players = Mockito.mock(PlayerRepository.class);
        service = new PlayerService(players);
    }

    @Nested
    @DisplayName("resolving a caller")
    class Resolve {

        @Test
        void reusesTheProfileWhenTheDeviceIsKnown() {
            when(players.findIdByDevice("dev-abc")).thenReturn(Optional.of(EXISTING));

            assertThat(service.resolveOrCreate(CALLER)).isEqualTo(EXISTING);

            verify(players).touch(EXISTING, "127.0.0.1");
            verify(players, never()).insertIfFree(anyString(), anyString(), anyString());
        }

        @Test
        void createsAProfileTheFirstTimeADeviceAppears() {
            when(players.findIdByDevice("dev-abc")).thenReturn(Optional.empty());
            when(players.insertIfFree(eq("dev-abc"), anyString(), eq("127.0.0.1")))
                    .thenReturn(Optional.of(CREATED));

            assertThat(service.resolveOrCreate(CALLER)).isEqualTo(CREATED);
        }

        @Test
        void generatesASixDigitDefaultNameThatPassesTheNameRules() {
            when(players.findIdByDevice(anyString())).thenReturn(Optional.empty());
            when(players.insertIfFree(anyString(), anyString(), anyString())).thenReturn(Optional.of(CREATED));

            service.resolveOrCreate(CALLER);

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(players).insertIfFree(anyString(), name.capture(), anyString());
            assertThat(name.getValue()).matches("User\\d{6}");
        }
    }

    @Nested
    @DisplayName("first contact races")
    class Races {

        /**
         * The regression that mattered: this used to be handled by catching
         * DuplicateKeyException, which cannot work inside a transaction because a raised
         * constraint violation aborts it. The insert must therefore report a clash by
         * returning nothing.
         */
        @Test
        void adoptsThePlayerCreatedByAConcurrentRequestForTheSameDevice() {
            when(players.findIdByDevice("dev-abc"))
                    .thenReturn(Optional.empty())      // nothing there when we looked
                    .thenReturn(Optional.of(EXISTING)); // someone inserted while we tried
            when(players.insertIfFree(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

            assertThat(service.resolveOrCreate(CALLER)).isEqualTo(EXISTING);
        }

        @Test
        void triesAnotherNameWhenTheGeneratedOneIsTaken() {
            when(players.findIdByDevice("dev-abc")).thenReturn(Optional.empty());
            when(players.insertIfFree(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty())       // that name was taken
                    .thenReturn(Optional.of(CREATED));  // the next one was free

            assertThat(service.resolveOrCreate(CALLER)).isEqualTo(CREATED);

            ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
            verify(players, Mockito.times(2)).insertIfFree(anyString(), names.capture(), anyString());
            assertThat(names.getAllValues()).doesNotHaveDuplicates();
        }

        @Test
        void givesUpRatherThanLoopingForeverIfEveryNameClashes() {
            when(players.findIdByDevice("dev-abc")).thenReturn(Optional.empty());
            when(players.insertIfFree(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveOrCreate(CALLER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("choosing a name")
    class Rename {

        @BeforeEach
        void deviceIsKnown() {
            when(players.findIdByDevice("dev-abc")).thenReturn(Optional.of(EXISTING));
        }

        @ParameterizedTest
        @ValueSource(strings = { "abc", "Tarik", "Kaptan_42", "_", "aaaaaaaaaaaaaaaa" })
        void acceptsNamesWithinTheRules(String name) {
            if (name.length() < 3) return;   // "_" is covered by the too-short case below
            when(players.rename(EXISTING, name)).thenReturn(true);
            when(players.find(EXISTING)).thenReturn(Optional.of(
                    new Player(EXISTING, "dev-abc", name, null)));

            assertThat(service.rename(CALLER, name).displayName()).isEqualTo(name);
        }

        @ParameterizedTest
        @ValueSource(strings = { "ab", "a", "aaaaaaaaaaaaaaaaa" })
        void rejectsNamesOfTheWrongLength(String name) {
            assertThatThrownBy(() -> service.rename(CALLER, name))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageContaining("characters");
        }

        @ParameterizedTest
        @ValueSource(strings = { "Tarik Usta", "tarik!", "ali-veli", "emoji😀", "nokta." })
        void rejectsNamesWithDisallowedCharacters(String name) {
            assertThatThrownBy(() -> service.rename(CALLER, name))
                    .isInstanceOf(InvalidInputException.class);
        }

        @Test
        void trimsBeforeJudgingAndBeforeStoring() {
            when(players.rename(EXISTING, "Tarik")).thenReturn(true);
            when(players.find(EXISTING)).thenReturn(Optional.of(
                    new Player(EXISTING, "dev-abc", "Tarik", null)));

            service.rename(CALLER, "   Tarik   ");

            verify(players).rename(EXISTING, "Tarik");
        }

        @Test
        void reportsAClashRatherThanOverwritingSomeoneElse() {
            when(players.rename(any(), anyString())).thenThrow(new DuplicateKeyException("unique"));

            assertThatThrownBy(() -> service.rename(CALLER, "Tarik"))
                    .isInstanceOf(AlreadyTakenException.class);
        }

        @Test
        void reportsAMissingPlayerRatherThanClaimingSuccess() {
            when(players.rename(any(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.rename(CALLER, "Tarik"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> service.rename(CALLER, null))
                    .isInstanceOf(InvalidInputException.class);
        }
    }
}
