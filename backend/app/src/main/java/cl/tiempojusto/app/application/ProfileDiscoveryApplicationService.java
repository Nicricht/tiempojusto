package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProfileDiscoveryApplicationService {
    private final JdbcTemplate jdbc;

    public ProfileDiscoveryApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ProfileView getPublicProfile(UUID actorUserId, UUID profileId) {
        ProfileView profile = loadProfile(profileId);
        if (profile.userId().equals(actorUserId)) return profile;
        if (isBlockedEitherDirection(actorUserId, profile.userId())) {
            throw ApiProblem.notFound("PROFILE_NOT_FOUND", "Perfil no disponible.");
        }
        if (!"ACTIVE".equals(profile.profileStatus()) || !profile.modalities().contains("ONLINE")) {
            throw ApiProblem.notFound("PROFILE_NOT_FOUND", "Perfil no disponible.");
        }
        return profile;
    }

    @Transactional(readOnly = true)
    public DiscoveryPage discover(UUID actorUserId, String modality, Integer limit) {
        String normalizedModality = modality == null || modality.isBlank() ? "ONLINE" : modality.trim().toUpperCase();
        if (!"ONLINE".equals(normalizedModality)) {
            throw ApiProblem.badRequest("MVP_ONLINE_ONLY", "MVP Production V1 solo publica Discovery ONLINE.");
        }
        int requested = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        int queryLimit = requested + 1;

        List<UUID> ids = jdbc.query("""
                select hp.id
                  from profile.host_profile hp
                  join iam.app_user u on u.id = hp.user_id
                 where hp.profile_status = 'ACTIVE'
                   and hp.supports_online = true
                   and u.account_status = 'ACTIVE'
                   and hp.user_id <> ?
                   and exists (
                       select 1
                         from iam.identity_verification iv
                        where iv.user_id = hp.user_id
                          and iv.status = 'VERIFIED'
                          and iv.verified_adult = true
                   )
                   and not exists (
                       select 1
                         from iam.user_block b
                        where (b.blocker_user_id = ? and b.blocked_user_id = hp.user_id)
                           or (b.blocker_user_id = hp.user_id and b.blocked_user_id = ?)
                   )
                 order by hp.created_at asc, hp.id asc
                 limit ?
                """, (rs, n) -> rs.getObject(1, UUID.class), actorUserId, actorUserId, actorUserId, queryLimit);

        boolean hasMore = ids.size() > requested;
        if (hasMore) ids = ids.subList(0, requested);
        List<ProfileView> items = ids.stream().map(this::loadProfile).toList();
        return new DiscoveryPage(items, null, hasMore);
    }

    private ProfileView loadProfile(UUID profileId) {
        List<ProfileBase> rows = jdbc.query("""
                select hp.id,
                       hp.user_id,
                       hp.description,
                       hp.supports_in_person,
                       hp.supports_online,
                       hp.map_visible,
                       hp.profile_status::text,
                       pi.username::text,
                       pi.display_name,
                       pi.public_age,
                       pi.bio,
                       u.lock_version,
                       case when hp.map_visible then (
                           select al.cell_code
                             from geo.approximate_location al
                            where al.profile_id = hp.id
                              and (al.valid_until is null or al.valid_until > clock_timestamp())
                            order by al.valid_from desc
                            limit 1
                       ) else null end as approximate_zone
                  from profile.host_profile hp
                  join iam.app_user u on u.id = hp.user_id
                  join iam.public_identity pi on pi.user_id = hp.user_id
                 where hp.id = ?
                """, (rs, n) -> new ProfileBase(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                firstNonBlank(rs.getString("description"), rs.getString("bio")),
                (Integer) rs.getObject("public_age"),
                rs.getBoolean("supports_in_person"),
                rs.getBoolean("supports_online"),
                rs.getString("approximate_zone"),
                rs.getBoolean("map_visible"),
                rs.getString("profile_status"),
                rs.getInt("lock_version") + 1
        ), profileId);
        if (rows.isEmpty()) throw ApiProblem.notFound("PROFILE_NOT_FOUND", "Perfil no existe.");

        ProfileBase row = rows.getFirst();
        Map<String, String> attributes = new LinkedHashMap<>();
        jdbc.query("""
                select attribute_key, attribute_value
                  from profile.profile_attribute
                 where profile_id = ?
                 order by attribute_key
                """, rs -> {
            while (rs.next()) attributes.put(rs.getString(1), rs.getString(2));
            return null;
        }, profileId);

        List<String> modalities = new java.util.ArrayList<>(2);
        if (row.supportsInPerson()) modalities.add("IN_PERSON");
        if (row.supportsOnline()) modalities.add("ONLINE");

        return new ProfileView(
                row.id(), row.userId(), row.username(), row.displayName(), row.bio(), row.publicAge(),
                List.copyOf(modalities), row.approximateZone(), row.mapVisible(), Map.copyOf(attributes),
                row.profileStatus(), row.version());
    }

    private boolean isBlockedEitherDirection(UUID actorUserId, UUID otherUserId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from iam.user_block
                 where (blocker_user_id = ? and blocked_user_id = ?)
                    or (blocker_user_id = ? and blocked_user_id = ?)
                """, Integer.class, actorUserId, otherUserId, otherUserId, actorUserId);
        return count != null && count > 0;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private record ProfileBase(UUID id, UUID userId, String username, String displayName, String bio,
                               Integer publicAge, boolean supportsInPerson, boolean supportsOnline,
                               String approximateZone, boolean mapVisible, String profileStatus, int version) {}

    public record ProfileView(UUID id, UUID userId, String username, String displayName, String bio,
                              Integer publicAge, List<String> modalities, String approximateZone,
                              boolean mapVisible, Map<String, String> selfDeclaredAttributes,
                              String profileStatus, int version) {}

    public record DiscoveryPage(List<ProfileView> items, String nextCursor, boolean hasMore) {}
}
