package cl.tiempojusto.statemachine.safety;
public enum SafetyLevel {
    S0(0), S1(0), S2(24), S3(7*24), S4(30*24), S5(-1);
    private final int durationHours;
    SafetyLevel(int durationHours){ this.durationHours=durationHours; }
    public int durationHours(){ return durationHours; }
    public boolean irreversibleOrIndefinite(){ return this==S5; }
}
