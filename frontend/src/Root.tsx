import { useEffect, useState } from 'react';
import App from './App';
import IdentityPage from './features/IdentityPage';
import ProposalPage from './features/ProposalPage';

type Surface = 'runtime' | 'identity' | 'proposal';

function fromHash(): Surface {
  if (window.location.hash === '#/identity') return 'identity';
  if (window.location.hash === '#/proposal') return 'proposal';
  return 'runtime';
}

export default function Root() {
  const [surface, setSurface] = useState<Surface>(() => fromHash());

  useEffect(() => {
    const sync = () => setSurface(fromHash());
    window.addEventListener('hashchange', sync);
    return () => window.removeEventListener('hashchange', sync);
  }, []);

  function navigate(next: Surface): void {
    const hash = next === 'identity' ? '#/identity' : next === 'proposal' ? '#/proposal' : '#/';
    window.location.hash = hash;
    setSurface(next);
  }

  return (
    <>
      <aside className="mvp-navigator" aria-label="Módulos MVP">
        <span>Flujo MVP</span>
        <button className={surface === 'runtime' ? 'active' : ''} onClick={() => navigate('runtime')}>Core</button>
        <button className={surface === 'identity' ? 'active' : ''} onClick={() => navigate('identity')}>Identidad</button>
        <button className={surface === 'proposal' ? 'active' : ''} onClick={() => navigate('proposal')}>Proposal</button>
      </aside>
      {surface === 'runtime' && <App />}
      {surface === 'identity' && <IdentityPage />}
      {surface === 'proposal' && <ProposalPage />}
    </>
  );
}
