package cl.tiempojusto.app.application;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.BidReservationCoordinator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BidReservationReleaseService {
    private final JdbcTemplate jdbc;
    private final BidReservationCoordinator reservations;

    public BidReservationReleaseService(JdbcTemplate jdbc, BidReservationCoordinator reservations) {
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${tiempojusto.auction.reservation-release-delay-ms:5000}")
    @Transactional
    public void processPending() {
        Boolean tableReady = jdbc.queryForObject(
                "select to_regclass('auction.reservation_replacement') is not null",
                Boolean.class
        );
        if (!Boolean.TRUE.equals(tableReady)) return;

        List<Row> rows = jdbc.query("""
                select id, prior_reservation_id
                  from auction.reservation_replacement
                 where state = 'RELEASE_PENDING'
                 order by created_at
                 for update skip locked
                 limit 20
                """, (rs, n) -> new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("prior_reservation_id", UUID.class)));

        for (Row row : rows) {
            Instant now = Instant.now();
            try {
                reservations.releaseSuperseded(
                        row.priorReservationId(),
                        "reservation-replacement:" + row.id(),
                        now
                );
                jdbc.update("""
                        update auction.funds_reservation
                           set status = 'RELEASED'
                         where id = ? and status = 'RESERVED'
                        """, row.priorReservationId());
                jdbc.update("""
                        update auction.reservation_replacement
                           set state = 'RELEASED',
                               attempt_count = attempt_count + 1,
                               last_attempt_at = ?,
                               last_error_code = null,
                               released_at = ?
                         where id = ? and state = 'RELEASE_PENDING'
                        """, Timestamp.from(now), Timestamp.from(now), row.id());
            } catch (FinanceException ex) {
                jdbc.update("""
                        update auction.reservation_replacement
                           set attempt_count = attempt_count + 1,
                               last_attempt_at = ?,
                               last_error_code = ?
                         where id = ? and state = 'RELEASE_PENDING'
                        """, Timestamp.from(now), ex.code(), row.id());
            } catch (RuntimeException ex) {
                jdbc.update("""
                        update auction.reservation_replacement
                           set attempt_count = attempt_count + 1,
                               last_attempt_at = ?,
                               last_error_code = 'UNEXPECTED_RELEASE_FAILURE'
                         where id = ? and state = 'RELEASE_PENDING'
                        """, Timestamp.from(now), row.id());
            }
        }
    }

    private record Row(UUID id, UUID priorReservationId) {}
}
