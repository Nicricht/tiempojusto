import { createHmac } from 'node:crypto';
import { chromium } from 'playwright';

const secret = process.env.TJ_TURN_SHARED_SECRET || 'ci-turn-shared-secret';
const turnUrl = process.env.TJ_TURN_URL || 'turn:127.0.0.1:3478?transport=udp';
const probeUrl = process.env.TJ_WEBRTC_PROBE_URL || 'http://127.0.0.1:4173/webrtc-relay.html';

function credential(user) {
  const expires = Math.floor(Date.now() / 1000) + 600;
  const username = `${expires}:${user}`;
  const value = createHmac('sha1', secret).update(username).digest('base64');
  return { urls: [turnUrl], username, credential: value };
}

async function launch() {
  return chromium.launch({
    headless: true,
    args: [
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
      '--autoplay-policy=no-user-gesture-required',
    ],
  });
}

async function initialize(page, iceServer) {
  await page.goto(probeUrl);
  await page.evaluate(async (server) => {
    const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
    if (!stream.getVideoTracks().some((track) => track.readyState === 'live')) {
      throw new Error('fake camera did not produce a live video track');
    }
    const pc = new RTCPeerConnection({ iceServers: [server], iceTransportPolicy: 'relay' });
    const remote = new MediaStream();
    pc.ontrack = (event) => remote.addTrack(event.track);
    for (const track of stream.getTracks()) pc.addTrack(track, stream);
    window.__tj = { pc, stream, remote };
  }, iceServer);
}

async function localDescription(page, type) {
  return page.evaluate(async (descriptionType) => {
    const { pc } = window.__tj;
    const description = descriptionType === 'offer' ? await pc.createOffer() : await pc.createAnswer();
    await pc.setLocalDescription(description);
    if (pc.iceGatheringState !== 'complete') {
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('ICE gathering timeout')), 20_000);
        const listener = () => {
          if (pc.iceGatheringState === 'complete') {
            clearTimeout(timeout);
            pc.removeEventListener('icegatheringstatechange', listener);
            resolve();
          }
        };
        pc.addEventListener('icegatheringstatechange', listener);
      });
    }
    return { type: pc.localDescription.type, sdp: pc.localDescription.sdp };
  }, type);
}

async function setRemote(page, description) {
  await page.evaluate(async (value) => {
    await window.__tj.pc.setRemoteDescription(value);
  }, description);
}

async function waitConnected(page) {
  await page.waitForFunction(() => window.__tj?.pc?.connectionState === 'connected', null, { timeout: 25_000 });
}

async function selectedPath(page) {
  return page.evaluate(async () => {
    const { pc, remote } = window.__tj;
    const stats = await pc.getStats();
    let pair;
    for (const stat of stats.values()) {
      if (stat.type === 'transport' && stat.selectedCandidatePairId) {
        pair = stats.get(stat.selectedCandidatePairId);
        break;
      }
    }
    if (!pair) {
      for (const stat of stats.values()) {
        if (stat.type === 'candidate-pair' && stat.state === 'succeeded' && stat.nominated) {
          pair = stat;
          break;
        }
      }
    }
    if (!pair) throw new Error('no selected candidate pair');
    const local = stats.get(pair.localCandidateId);
    const remoteCandidate = stats.get(pair.remoteCandidateId);
    return {
      connectionState: pc.connectionState,
      localType: local?.candidateType ?? null,
      remoteType: remoteCandidate?.candidateType ?? null,
      remoteVideoTracks: remote.getVideoTracks().length,
      remoteAudioTracks: remote.getAudioTracks().length,
    };
  });
}

const firstBrowser = await launch();
const secondBrowser = await launch();
try {
  const first = await firstBrowser.newPage();
  const second = await secondBrowser.newPage();
  await initialize(first, credential('host-ci'));
  await initialize(second, credential('bidder-ci'));

  const offer = await localDescription(first, 'offer');
  await setRemote(second, offer);
  const answer = await localDescription(second, 'answer');
  await setRemote(first, answer);

  await Promise.all([waitConnected(first), waitConnected(second)]);
  await new Promise((resolve) => setTimeout(resolve, 1000));

  const [firstPath, secondPath] = await Promise.all([selectedPath(first), selectedPath(second)]);
  for (const [name, value] of [['first', firstPath], ['second', secondPath]]) {
    if (value.connectionState !== 'connected') throw new Error(`${name} browser is not connected`);
    if (value.localType !== 'relay') throw new Error(`${name} browser did not select a relay local candidate: ${JSON.stringify(value)}`);
    if (value.remoteVideoTracks < 1) throw new Error(`${name} browser did not receive remote video`);
  }

  console.log('PASS: two independent Chromium browsers connected with camera through TURN relay');
  console.log(JSON.stringify({ firstPath, secondPath }, null, 2));
} finally {
  await Promise.allSettled([firstBrowser.close(), secondBrowser.close()]);
}
