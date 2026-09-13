import { useEffect, useState } from 'react';
import App from './App';
import AdminPage from './features/AdminPage';
import GoldenPathPage from './features/GoldenPathPage';
import IdentityPage from './features/IdentityPage';
import OnlineVideoPage from './features/OnlineVideoPage';
import ProposalPage from './features/ProposalPage';

type Surface = 'flow' | 'runtime' | 'identity' | 'proposal' | 'video' | 'admin';

function fromHash(): Surface {
  if (window.location.hash === '#/identity') return 'identity';
  if (window.location.hash === '#/proposal') return 'proposal';
  if (window.location.hash === '#/video') return 'video';
  if (window.location.hash === '#/admin') return 'admin';
  if (window.location.hash === '#/core') return 'runtime';
  return 'flow';
}

export default function Root() {
  const [surface, setSurface] = useState<Surface>(() => fromHash());

  useEffect(() => {
    const sync = () => setSurface(fromHash());
    window.addEventListener('hashchange', sync);
    return () => window.removeEventListener('hashchange', sync);
  }, []);

  function navigate(next: Surface): void {
    const hash = next === 'flow'
      ? '#/flow'
      : next === 'identity'
        ? '#/identity'
        : next === 'proposal'
          ? '#/proposal'
          : next === 'video'
            ? '#/video'
            : next === 'admin'
              ? '#/admin'
              : '#/core';
    window.location.hash = hash;
    setSurface(next);
  }

  return (
    <>
      <aside className="mvp-navigator" aria-label="Módulos MVP">
        <span>Flujo MVP</span>
        <button className={surface === 'flow' ? 'active' : ''} onClick={() => navigate('flow')}>Golden Path</button>
        <button className={surface === 'runtime' ? 'active' : ''} onClick={() => navigate('runtime')}>Core</button>
        <button className={surface === 'identity' ? 'active' : ''} onClick={() => navigate('identity')}>Identidad</button>
        <button className={surface === 'proposal' ? 'active' : ''} onClick={() => navigate('proposal')}>Proposal</button>
        <button className={surface === 'video' ? 'active' : ''} onClick={() => navigate('video')}>Video</button>
        <button className={surface === 'admin' ? 'active' : ''} onClick={() => navigate('admin')}>Admin</button>
      </aside>
      {surface === 'flow' && <GoldenPathPage />}
      {surface === 'runtime' && <App />}
      {surface === 'identity' && <IdentityPage />}
      {surface === 'proposal' && <ProposalPage />}
      {surface === 'video' && <OnlineVideoPage />}
      {surface === 'admin' && <AdminPage />}
    </>
  );
}
