package cl.tiempojusto.media;

import java.time.Instant;
import java.util.UUID;

public final class MediaContractTests {
    private static final Instant T0=Instant.parse("2026-08-14T12:00:00Z");
    private static int pass=0;
    public static void main(String[] args) {
        run("online join window exactly 3 minutes", MediaContractTests::joinWindow);
        run("both cameras required before free-ready", MediaContractTests::cameraJoinGuard);
        run("private Online recording is disabled", MediaContractTests::privateRecording);
        run("paid media requires both valid cameras", MediaContractTests::paidCameraGuard);
        run("audio mute alone does not interrupt billing", MediaContractTests::audioMute);
        run("microcut at 5 seconds is tolerated", MediaContractTests::microcutTolerance);
        run("confirmed interruption pauses from last common valid heartbeat", MediaContractTests::retroPause);
        run("Online reconnect window is exactly 2 minutes", MediaContractTests::onlineReconnectWindow);
        run("media recovery alone does not resume paid state", MediaContractTests::recoveryNeedsConsent);
        run("bilateral acceptance resumes after recovered media", MediaContractTests::bilateralResume);
        run("reconnect timeout ends media room", MediaContractTests::onlineReconnectTimeout);
        run("TURN credentials are ephemeral and scoped", MediaContractTests::turnCredentials);
        run("provider outage is explicit", MediaContractTests::providerFailure);
        run("Live requires at least 5 minutes remaining", MediaContractTests::liveStartGuard);
        run("Live reconnect window is 2 minutes", MediaContractTests::liveReconnect);
        run("Live outage never pauses Auction", MediaContractTests::liveAuctionIndependence);
        run("Live has no persistent recording by default", MediaContractTests::liveRecording);
        System.out.println("PASS: "+pass+"/17 Media contract tests");
    }
    private static OnlineMediaRoom ready(MockWebRtcPort p) {
        OnlineMediaRoom r=OnlineMediaRoom.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),p,T0);
        r=r.join(ParticipantRole.HOST,T0.plusSeconds(10),true,true);
        return r.join(ParticipantRole.BIDDER,T0.plusSeconds(20),true,true);
    }
    private static OnlineMediaRoom paid(MockWebRtcPort p) { return ready(p).markPaidActive(T0.plusSeconds(121)); }
    private static void joinWindow(){ var r=OnlineMediaRoom.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new MockWebRtcPort(),T0); eq(T0.plusSeconds(180),r.joinDeadline()); }
    private static void cameraJoinGuard(){ var p=new MockWebRtcPort(); var r=OnlineMediaRoom.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),p,T0).join(ParticipantRole.HOST,T0.plusSeconds(5),true,true).join(ParticipantRole.BIDDER,T0.plusSeconds(6),false,true); eq(MediaRoomState.JOIN_WINDOW,r.state()); }
    private static void privateRecording(){ var r=ready(new MockWebRtcPort()); ok(!r.persistentRecordingEnabled()); throwsCode("ONLINE_PRIVATE recording guard",()->new RoomHandle("x","y",RoomKind.ONLINE_PRIVATE,true),IllegalArgumentException.class); }
    private static void paidCameraGuard(){ var p=new MockWebRtcPort(); var r=ready(p).heartbeat(ParticipantRole.HOST,T0.plusSeconds(50),false,true,false); final var x=r; throwsMedia("ONLINE_CAMERA_REQUIRED",()->x.markPaidActive(T0.plusSeconds(60))); }
    private static void audioMute(){ var p=new MockWebRtcPort(); var r=paid(p).heartbeat(ParticipantRole.HOST,T0.plusSeconds(123),true,true,true).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(123),true,true,false).evaluatePaidMedia(T0.plusSeconds(124)); eq(MediaRoomState.PAID_ACTIVE,r.state()); }
    private static void microcutTolerance(){ var p=new MockWebRtcPort(); var r=paid(p).heartbeat(ParticipantRole.HOST,T0.plusSeconds(130),true,true,false).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(130),true,true,false); r=r.heartbeat(ParticipantRole.HOST,T0.plusSeconds(131),false,false,false).evaluatePaidMedia(T0.plusSeconds(135)); eq(MediaRoomState.PAID_ACTIVE,r.state()); }
    private static void retroPause(){ var p=new MockWebRtcPort(); var r=paid(p); r=r.heartbeat(ParticipantRole.HOST,T0.plusSeconds(130),true,true,false).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(130),true,true,false); r=r.heartbeat(ParticipantRole.HOST,T0.plusSeconds(131),false,false,false); r=r.evaluatePaidMedia(T0.plusSeconds(136)); eq(MediaRoomState.RECONNECTING,r.state()); eq(T0.plusSeconds(130),r.pauseBillingFrom()); }
    private static OnlineMediaRoom interrupted(MockWebRtcPort p){ var r=paid(p).heartbeat(ParticipantRole.HOST,T0.plusSeconds(130),true,true,false).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(130),true,true,false); r=r.heartbeat(ParticipantRole.HOST,T0.plusSeconds(131),false,false,false); return r.evaluatePaidMedia(T0.plusSeconds(136)); }
    private static void onlineReconnectWindow(){ var r=interrupted(new MockWebRtcPort()); eq(T0.plusSeconds(256),r.reconnectDeadline()); }
    private static void recoveryNeedsConsent(){ var r=interrupted(new MockWebRtcPort()).heartbeat(ParticipantRole.HOST,T0.plusSeconds(140),true,true,false).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(140),true,true,false).markRecovered(T0.plusSeconds(140)); eq(MediaRoomState.RECOVERED_AWAITING_BILATERAL_RESUME,r.state()); ok(!r.billableMediaState()); }
    private static void bilateralResume(){ var r=interrupted(new MockWebRtcPort()).heartbeat(ParticipantRole.HOST,T0.plusSeconds(140),true,true,false).heartbeat(ParticipantRole.BIDDER,T0.plusSeconds(140),true,true,false).markRecovered(T0.plusSeconds(140)).resumeAfterBilateralAcceptance(T0.plusSeconds(141),true,true); eq(MediaRoomState.PAID_ACTIVE,r.state()); ok(r.billableMediaState()); }
    private static void onlineReconnectTimeout(){ var r=interrupted(new MockWebRtcPort()).reconnectTimeout(T0.plusSeconds(256)); eq(MediaRoomState.ENDED,r.state()); }
    private static void turnCredentials(){ var p=new MockWebRtcPort(); var r=ready(p); var c=p.issueTurnCredentials(r.room(),r.host().userId(),T0); eq(T0.plusSeconds(600),c.expiresAt()); ok(c.username().contains(r.host().userId().toString())); }
    private static void providerFailure(){ var p=new MockWebRtcPort(); p.setHealthy(false); throwsMedia("WEBRTC_PROVIDER_UNAVAILABLE",()->OnlineMediaRoom.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),p,T0)); }
    private static void liveStartGuard(){ throwsMedia("LIVE_TOO_LATE",()->LiveMediaRoom.start(UUID.randomUUID(),299,new MockWebRtcPort(),T0)); }
    private static void liveReconnect(){ var r=LiveMediaRoom.start(UUID.randomUUID(),600,new MockWebRtcPort(),T0).confirmTransmissionLoss(T0.plusSeconds(10)); eq(T0.plusSeconds(130),r.reconnectDeadline()); eq(MediaRoomState.LIVE,r.recover(T0.plusSeconds(100)).state()); }
    private static void liveAuctionIndependence(){ var r=LiveMediaRoom.start(UUID.randomUUID(),600,new MockWebRtcPort(),T0).confirmTransmissionLoss(T0.plusSeconds(10)); ok(r.auctionContinues()); }
    private static void liveRecording(){ var r=LiveMediaRoom.start(UUID.randomUUID(),600,new MockWebRtcPort(),T0); ok(!r.persistentRecordingEnabled()); }
    private static void run(String n,Runnable r){ try{r.run();pass++;System.out.println("PASS "+pass+" - "+n);}catch(Throwable t){System.err.println("FAIL - "+n+": "+t);System.exit(1);} }
    private static void ok(boolean v){ if(!v) throw new AssertionError(); }
    private static void eq(Object e,Object a){ if(!java.util.Objects.equals(e,a)) throw new AssertionError("expected="+e+" actual="+a); }
    private static void throwsMedia(String code,Runnable r){ try{r.run();throw new AssertionError("expected "+code);}catch(MediaException e){eq(code,e.code());} }
    private static void throwsCode(String name,Runnable r,Class<? extends Throwable> c){ try{r.run();throw new AssertionError("expected "+name);}catch(Throwable e){if(!c.isInstance(e)) throw new AssertionError(e);} }
}
