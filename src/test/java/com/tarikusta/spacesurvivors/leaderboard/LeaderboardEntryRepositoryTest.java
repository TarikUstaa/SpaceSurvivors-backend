package com.tarikusta.spacesurvivors.leaderboard;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leaderboard queries against a real Postgres. Three of the four cannot be trusted
 * without one: the JPQL join has to produce valid SQL, the rank sub-query has to agree
 * with the board's ordering, and the upsert has to genuinely replace rather than append.
 */
@DatabaseTest
@Transactional
class LeaderboardEntryRepositoryTest {

    private static final String MODE = "infinite";

    @Autowired
    private LeaderboardEntryRepository board;

    @Autowired
    private JdbcClient db;

    private UUID player(String displayName) {
        return db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name) RETURNING player_id
                        """)
                .param("device", "test-" + UUID.randomUUID())
                .param("name", displayName)
                .query(UUID.class)
                .single();
    }

    @Test
    @DisplayName("the upsert replaces the row rather than appending one")
    void savingTwiceKeepsOneRowPerPlayerPerMode() {
        UUID id = player("Ada" + System.nanoTime() % 100000);

        board.saveBest(id, MODE, 300, 420, 18, 1);
        board.saveBest(id, MODE, 600, 900, 30, 3);

        Integer rows = db.sql("SELECT count(*) FROM leaderboard WHERE player_id = :id")
                .param("id", id).query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(board.findPersonalBest(id, MODE)).contains(600.0);
    }

    @Test
    @DisplayName("the board is ordered best first and exposes only display names")
    void topEntriesJoinsThePlayerAndOrdersByTime() {
        String fast = "Fast" + System.nanoTime() % 100000;
        String slow = "Slow" + System.nanoTime() % 100000;
        board.saveBest(player(slow), MODE, 300, 420, 18, 1);
        board.saveBest(player(fast), MODE, 600, 900, 30, 3);

        List<BoardRow> rows = board.topEntries(MODE, PageRequest.ofSize(10));

        assertThat(rows).extracting(BoardRow::displayName).containsSubsequence(fast, slow);
        assertThat(rows).extracting(BoardRow::survivedSeconds).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void pageableLimitsTheNumberOfRows() {
        board.saveBest(player("A" + System.nanoTime() % 100000), MODE, 100, 10, 2, 0);
        board.saveBest(player("B" + System.nanoTime() % 100000), MODE, 200, 20, 3, 0);
        board.saveBest(player("C" + System.nanoTime() % 100000), MODE, 300, 30, 4, 0);

        assertThat(board.topEntries(MODE, PageRequest.ofSize(2))).hasSize(2);
    }

    @Test
    @DisplayName("rank agrees with the position the board would show")
    void standingMatchesTheBoardOrder() {
        UUID first = player("First" + System.nanoTime() % 100000);
        UUID second = player("Second" + System.nanoTime() % 100000);
        UUID third = player("Third" + System.nanoTime() % 100000);
        board.saveBest(first, MODE, 900, 900, 40, 4);
        board.saveBest(second, MODE, 600, 600, 30, 3);
        board.saveBest(third, MODE, 300, 300, 20, 2);

        // Relative, not absolute: other rows for this mode may exist in the test database.
        int firstRank = board.findStanding(first, MODE).orElseThrow().getRank();
        int secondRank = board.findStanding(second, MODE).orElseThrow().getRank();
        int thirdRank = board.findStanding(third, MODE).orElseThrow().getRank();

        assertThat(firstRank).isLessThan(secondRank);
        assertThat(secondRank).isLessThan(thirdRank);
        assertThat(board.findStanding(second, MODE).orElseThrow().getSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("a player with no entry has no standing, rather than rank 1")
    void standingIsEmptyForSomeoneNotOnTheBoard() {
        // The trap the query is shaped to avoid: a flat COUNT(*) + 1 returns one row
        // holding 1, which would tell a player who has never submitted that they lead.
        assertThat(board.findStanding(player("Absent" + System.nanoTime() % 100000), MODE)).isEmpty();
    }

    @Test
    void modesAreRankedSeparately() {
        UUID id = player("Both" + System.nanoTime() % 100000);
        board.saveBest(id, MODE, 300, 300, 20, 2);

        assertThat(board.findPersonalBest(id, MODE)).contains(300.0);
        assertThat(board.findPersonalBest(id, "campaign")).isEmpty();
    }
}
