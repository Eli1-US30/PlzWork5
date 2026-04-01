package org.example.files;

/**
 * Holds all stats for one team used in simulation.
 *
 * New in this version (feature 6):
 * - homeAdvantage field — calculated from the team's own home/away
 *   win rate split across their last N matches rather than using
 *   the flat 0.15 constant for everyone.
 *   Set by StatsCalculator, read by Simulator.
 *   Falls back to the global constant if insufficient data.
 */
public class Team {

    private final int    id;
    private final String name;

    // Combined stats (fallback if home/away splits have thin data)
    private double attack;
    private double defense;
    private double form;
    private double elo;

    // Home/away splits — set to -1 if insufficient data (triggers fallback)
    private double homeAttack   = -1;
    private double homeDefense  = -1;
    private double awayAttack   = -1;
    private double awayDefense  = -1;

    // Feature 6 — per-team home advantage
    // -1 means not enough data — Simulator uses global constant (0.15)
    private double homeAdvantage = -1;

    public Team(int id, String name) {
        this.id      = id;
        this.name    = name;
        this.elo     = 1500;
        this.attack  = 1.2;
        this.defense = 1.2;
        this.form    = 0.5;
    }

    // --- Getters ---
    public int    getId()      { return id; }
    public String getName()    { return name; }
    public double getAttack()  { return attack; }
    public double getDefense() { return defense; }
    public double getForm()    { return form; }
    public double getElo()     { return elo; }

    /**
     * Returns home attack if we have enough home data,
     * otherwise falls back to combined attack rating.
     */
    public double getHomeAttack() {
        return homeAttack >= 0 ? homeAttack : attack;
    }

    public double getHomeDefense() {
        return homeDefense >= 0 ? homeDefense : defense;
    }

    public double getAwayAttack() {
        return awayAttack >= 0 ? awayAttack : attack;
    }

    public double getAwayDefense() {
        return awayDefense >= 0 ? awayDefense : defense;
    }

    /**
     * Returns this team's calculated home advantage multiplier.
     * Returns -1 if not enough data — Simulator will use global constant.
     */
    public double getHomeAdvantage() {
        return homeAdvantage;
    }

    // --- Setters ---
    public void setAttack(double attack)           { this.attack        = attack; }
    public void setDefense(double defense)         { this.defense       = defense; }
    public void setForm(double form)               { this.form          = form; }
    public void setElo(double elo)                 { this.elo           = elo; }
    public void setHomeAttack(double v)            { this.homeAttack    = v; }
    public void setHomeDefense(double v)           { this.homeDefense   = v; }
    public void setAwayAttack(double v)            { this.awayAttack    = v; }
    public void setAwayDefense(double v)           { this.awayDefense   = v; }
    public void setHomeAdvantage(double v)         { this.homeAdvantage = v; }

    @Override
    public String toString() {
        return String.format(
                "%s [ELO=%.0f, Att=%.2f(H=%.2f/A=%.2f), " +
                        "Def=%.2f(H=%.2f/A=%.2f), Form=%.2f, HomeAdv=%.2f]",
                name, elo,
                attack,
                homeAttack  >= 0 ? homeAttack  : attack,
                awayAttack  >= 0 ? awayAttack  : attack,
                defense,
                homeDefense >= 0 ? homeDefense : defense,
                awayDefense >= 0 ? awayDefense : defense,
                form,
                homeAdvantage >= 0 ? homeAdvantage : 0.15);
    }
}