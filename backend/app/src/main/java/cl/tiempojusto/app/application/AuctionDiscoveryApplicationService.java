package cl.tiempojusto.app.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuctionDiscoveryApplicationService {
    private final JdbcTemplate jdbc;
    private final AuctionApplicationService auctions;

    public AuctionDiscoveryApplicationService(JdbcTemplate jdbc, AuctionApplicationService auctions) {
        this.jdbc = jdbc;
        this.auctions = auctions;
    }

    @Transactional(readOnly = true)
    public Page listOpen(UUID actorUserId, Integer limit) {
        int requested = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        int queryLimit = requested + 1;
        List<UUID> ids = jdbc.query("""
                select a.id
                  from auction.auction a
                 where a.status = 'OPEN'
                   and a.modality = 'ONLINE'
                   and a.effective_end_at > clock_timestamp()
                   and a.host_user_id <> ?
                   and not exists (
                       select 1
                         from iam.user_block b
                        where (b.blocker_user_id = ? and b.blocked_user_id = a.host_user_id)
                           or (b.blocker_user_id = a.host_user_id and b.blocked_user_id = ?)
                   )
                 order by a.effective_end_at asc, a.id asc
                 limit ?
                """, (rs, n) -> rs.getObject(1, UUID.class), actorUserId, actorUserId, actorUserId, queryLimit);
        boolean hasMore = ids.size() > requested;
        if (hasMore) ids = ids.subList(0, requested);
        return new Page(ids.stream().map(auctions::get).toList(), null, hasMore);
    }

    public record Page(List<AuctionApplicationService.AuctionView> items, String nextCursor, boolean hasMore) {}
}
