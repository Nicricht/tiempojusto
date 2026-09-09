package cl.tiempojusto.statemachine.winner;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record WinnerConfirmation(WinnerState state, int candidateNo, Instant selectedAt, Instant deadline, int penaltyPercent, boolean backupsClosed) {
    public static WinnerConfirmation select(Instant now, int candidateNo) {
        if (candidateNo < 1 || candidateNo > 4) throw TransitionException.of("WINNER_CANDIDATE_INVALID", "candidateNo 1=winner, 2..4=up to three backups");
        return new WinnerConfirmation(WinnerState.SELECTED, candidateNo, now, now.plusSeconds(180), 0, false);
    }
    public WinnerConfirmation confirm(Instant now) {
        requireSelected();
        if (now.isAfter(deadline)) throw TransitionException.of("WINNER_CONFIRM_TIMEOUT", "Three-minute confirmation window expired");
        return new WinnerConfirmation(WinnerState.CONFIRMED, candidateNo, selectedAt, deadline, 0, true);
    }
    public WinnerConfirmation timeout(Instant now) {
        requireSelected();
        if (now.isBefore(deadline)) throw TransitionException.of("WINNER_NOT_DUE", "Confirmation timeout is not due");
        return new WinnerConfirmation(WinnerState.TIMED_OUT, candidateNo, selectedAt, deadline, 10, false);
    }
    public WinnerConfirmation nextBackup(Instant now) {
        if (state != WinnerState.TIMED_OUT) throw TransitionException.of("BACKUP_BAD_STATE", "Backup can only follow a timeout");
        if (candidateNo >= 4) throw TransitionException.of("BACKUP_EXHAUSTED", "Maximum three backups exhausted");
        return select(now, candidateNo + 1);
    }
    private void requireSelected() { if (state != WinnerState.SELECTED) throw TransitionException.of("WINNER_BAD_STATE", "Winner is not awaiting confirmation"); }
}
