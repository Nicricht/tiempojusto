# Staging Secret Boundary

Secret values must live in the deployment platform secret store or host environment and must never be committed, passed as `VITE_*`, printed by CI, embedded in container images, or copied into issue comments.

The committed `.env.example` contains names/placeholders only. Before deployment, load real values outside Git and run `docker compose` from that environment.

Rotate immediately if a real credential is ever exposed in Git history, CI logs or public issue/PR text. Do not treat deleting the latest commit as sufficient rotation.
