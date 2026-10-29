# jenkins-atom-docker-build

Docker build — port of **docker-workflow（构建部分）** (89,052) as an atom.

Not docker-workflow — an atom is one process and cannot wrap a stage in a container. It can build, tag and
push, with `docker login --password-stdin` so the credential never reaches argv.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
