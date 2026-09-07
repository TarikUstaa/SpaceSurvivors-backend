package com.tarikusta.spacesurvivors.leaderboard;

/**
 * One row of the public board, straight out of the query and before a rank is stamped on
 * it. A top-level type because JPQL constructor expressions name it by its full class name.
 */
public record BoardRow(String displayName, double survivedSeconds, int kills, int reachedLevel) {
}
