package cl.tiempojusto.statemachine.presential;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record Appointment(AppointmentState state, Instant expectedArrivalAt, Instant arrivalAt, Instant handshakeDeadline, boolean handshakeExtraUsed) {
    public static Appointment enRoute(Instant now, int etaMinutes) {
        if (etaMinutes < 0 || etaMinutes > 30) throw TransitionException.of("ETA_INELIGIBLE", "Presential ETA must be <= 30 minutes");
        return new Appointment(AppointmentState.EN_ROUTE, now.plusSeconds(etaMinutes*60L), null, null, false);
    }
    public Appointment arrive(Instant now, boolean evidenceValid) {
        if (state != AppointmentState.EN_ROUTE) throw TransitionException.of("ARRIVAL_BAD_STATE", "Arrival requires EN_ROUTE");
        if (!evidenceValid) throw TransitionException.of("ARRIVAL_INVALID", "Arrival evidence invalid");
        Instant toleranceEnd = expectedArrivalAt.plusSeconds(10*60L);
        if (now.isAfter(toleranceEnd)) throw TransitionException.of("ARRIVAL_TOO_LATE", "Arrival exceeds ETA +10 minute tolerance");
        return new Appointment(AppointmentState.ARRIVED, expectedArrivalAt, now, now.plusSeconds(5*60L), false);
    }
    public Appointment extendHandshake(Instant now, boolean waiterGranted) {
        if (state != AppointmentState.ARRIVED) throw TransitionException.of("HANDSHAKE_BAD_STATE", "Handshake window is not active");
        if (handshakeExtraUsed) throw TransitionException.of("HANDSHAKE_EXTENSION_USED", "Only one +5 minute extension is allowed");
        if (!waiterGranted) throw TransitionException.of("HANDSHAKE_EXTENSION_NOT_GRANTED", "The waiting party must grant the extension");
        if (now.isAfter(handshakeDeadline)) throw TransitionException.of("HANDSHAKE_WINDOW_EXPIRED", "Base handshake window already expired");
        return new Appointment(state, expectedArrivalAt, arrivalAt, handshakeDeadline.plusSeconds(5*60L), true);
    }
    public Appointment confirmMeeting(Instant now, boolean hostConfirmed, boolean bidderConfirmed) {
        if (state != AppointmentState.ARRIVED) throw TransitionException.of("HANDSHAKE_BAD_STATE", "Meeting confirmation requires ARRIVED");
        if (now.isAfter(handshakeDeadline)) throw TransitionException.of("HANDSHAKE_EXPIRED", "Handshake window expired");
        if (!(hostConfirmed && bidderConfirmed)) throw TransitionException.of("HANDSHAKE_NOT_BILATERAL", "MeetingHandshake must be bilateral");
        return new Appointment(AppointmentState.MEETING_CONFIRMED, expectedArrivalAt, arrivalAt, handshakeDeadline, handshakeExtraUsed);
    }
}
