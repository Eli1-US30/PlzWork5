package org.example.files;

public class Match {

    private final int homeTeamId;
    private final int awayTeamId;
    private final String homeTeamName;
    private final String awayTeamName;
    private final int fixtureId; // API-Football fixture ID

    public Match(int fixtureId, int homeTeamId, String homeTeamName,
                 int awayTeamId, String awayTeamName) {
        this.fixtureId = fixtureId;
        this.homeTeamId = homeTeamId;
        this.homeTeamName = homeTeamName;
        this.awayTeamId = awayTeamId;
        this.awayTeamName = awayTeamName;
    }

    public int getFixtureId() { return fixtureId; }
    public int getHomeTeamId() { return homeTeamId; }
    public int getAwayTeamId() { return awayTeamId; }
    public String getHomeTeamName() { return homeTeamName; }
    public String getAwayTeamName() { return awayTeamName; }

    @Override
    public String toString() {
        return homeTeamName + " vs " + awayTeamName;
    }
}
