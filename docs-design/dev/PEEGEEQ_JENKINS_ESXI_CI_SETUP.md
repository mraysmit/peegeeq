# PeeGeeQ Jenkins CI on VMware ESXi

## Purpose

This guide describes how to build a Linux-based Jenkins environment on VMware ESXi for
PeeGeeQ. The target environment must support the complete Maven reactor, Docker-based
Testcontainers integration tests, both UI test suites, headed Playwright browser tests,
and the approximately 90-minute `all-tests` regression gate.

The recommended design separates Jenkins orchestration from test execution. A smaller
Jenkins controller schedules work on a dedicated, larger Linux build agent. A single
all-in-one VM is also suitable for a personal installation or a small team.

The selected container model is **Ubuntu's standard rootful `docker.io` package from the
Ubuntu 24.04 archives**. This is the project recommendation after comparing Canonical's
Ubuntu packaging with Docker's upstream `docker-ce` repository; the upstream repository
remains a valid alternative but is not the default in this guide. In the selected model,
the Docker daemon runs as `root` and Jenkins remains a non-root service account that
reaches the daemon through the root-owned Unix socket. Membership in the `docker` group
is therefore a privileged administrative boundary, not ordinary unprivileged access.

Jenkins itself is installed natively as a `systemd` service. It is not hosted in a
container. Docker exists on the build agent solely to run PeeGeeQ's PostgreSQL and other
Testcontainers workloads.

This guide complements:

- [PeeGeeQ Development Environment Setup](../../docs/PEEGEEQ_DEVELOPMENT_ENVIRONMENT_SETUP.md)
- [PeeGeeQ Test Commands](../testing/PEEGEEQ-TEST-COMMANDS.md)
- [PeeGeeQ Coding Principles](pgq-coding-principles.md)
- [PeeGeeQ Testing Standards and Antipatterns](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)

## Recommended architecture

### Preferred: separate controller and build agent

Use two VMs when the ESXi host has sufficient resources:

| VM | vCPU | Memory | Storage | Purpose |
|---|---:|---:|---:|---|
| Jenkins controller | 2-4 | 4-8 GB | 60 GB | Jenkins UI, job scheduling, credentials, and build history |
| PeeGeeQ Linux agent | 12 | 32 GB | 300 GB | Compilation, Docker, Testcontainers, Node, Playwright, and test execution |

Configure the controller with zero build executors. It should schedule all PeeGeeQ work
on an agent labelled `peegeeq-linux`. The controller does not need membership in the
Docker group and should not have access to a Docker socket. Only the dedicated agent
account receives Docker access. That account, and any Jenkins pipeline allowed to run as
it, must be treated as having root-equivalent control of the agent VM.

This design provides:

- isolation between Jenkins administration and privileged build activity;
- independent controller and worker upgrades;
- easier replacement or cloning of build agents;
- less risk that a resource-heavy test run makes the Jenkins UI unavailable; and
- a straightforward path to multiple test agents and module sharding later.

This separation does not make untrusted pipeline code safe. A pipeline running on the
Docker-enabled agent can control Docker and must be treated as privileged. Only trusted
repositories, reviewed Jenkinsfiles, and authorized job administrators may use this
agent.

### Simpler: one all-in-one VM

For a personal installation or small team, install Jenkins and the complete build stack
on one VM:

| Resource | Recommendation |
|---|---|
| Operating system | Ubuntu Server 24.04 LTS |
| CPU | 12 vCPUs; 8 vCPUs is a workable minimum |
| Memory | 32 GB; 24 GB is a workable minimum |
| Storage | Approximately 300 GB on a fast SSD/NVMe-backed datastore |
| Virtual NIC | VMXNET3 |
| Virtual disk controller | VMware PVSCSI where available |

In this model, Jenkins can run builds on its built-in node. Adding the `jenkins` account
to the Docker group is pragmatic on a dedicated internal VM, but it gives Jenkins
root-equivalent control of that VM. Do not use this arrangement on a shared or publicly
accessible server. Do not store unrelated workloads or long-lived production secrets on
the VM. Anyone who can alter or replay pipeline code on this node must be treated as an
administrator of the whole VM.

## Why these resources are appropriate

PeeGeeQ is a multi-module Java 25 project whose integration tests use real PostgreSQL
instances through Testcontainers. The complete regression profile also invokes the
management and utilities UI test suites, including Playwright end-to-end tests.

The current build intentionally limits concurrency in important places:

- default core tests run with four test threads;
- integration and complete-suite execution use conservative parallelism;
- the Maven reactor is sequential unless explicitly changed; and
- Playwright end-to-end tests use one worker.

Consequently, increasing a single VM from 12 to a very large number of vCPUs may not
produce a proportional reduction in the approximately 90-minute runtime. Fast local
storage, adequate memory, low ESXi contention, and isolated workers are more valuable
than extreme vCPU allocation.

Do not add Maven reactor parallelism initially. Establish a reliable baseline first.
Tests use containers, ports, timers, and integration resources, so aggressive
parallelism can expose contention rather than improve throughput.

## ESXi VM configuration

### CPU and memory

- Avoid heavy CPU overcommit on the ESXi host.
- Keep the build VM within one physical NUMA node where practical.
- Use a CPU reservation for repeatable performance-test measurements.
- Reserve memory for performance runs and avoid ballooning or swapping.
- Do not run other storage- or CPU-heavy guests during a performance baseline.

Correctness testing works without reservations, but performance results are not
comparable when ESXi contention varies between runs.

A small guest swap file is still useful as an emergency OOM buffer and prevents Jenkins'
free-swap node monitor from warning on a zero-swap host. It is not additional working
memory. Treat sustained swap use during a test as resource pressure, investigate it, and
do not publish performance comparisons from a swapping run. The provisioned VM uses a
2 GB root-owned `/swapfile`, mode `0600`, with `vm.swappiness=10` so the kernel prefers
RAM and uses swap only under pressure.

### Storage layout

A useful two-disk layout is:

| Disk | Suggested size | Contents |
|---|---:|---|
| OS disk | 60 GB | Ubuntu, Jenkins, installed tools |
| Build data disk | 240 GB or more | Jenkins workspaces, Maven cache, Docker data, logs, and browser artifacts |

Use ext4 or XFS on the Linux data disk. Place it on local SSD/NVMe-backed storage where
possible. Keep the Git checkout and Docker data local to the VM; avoid SMB, NFS, and
VMware shared folders for active builds.

Thin provisioning is acceptable if datastore free space is monitored. Test reports,
container layers, Maven artifacts, Playwright browser downloads, screenshots, and videos
can consume significant capacity over time.

### Networking

The build agent needs outbound access to:

- the Git repository;
- Maven Central and any configured Maven repositories;
- the npm registry;
- Docker Hub or the configured container registry;
- Playwright browser download endpoints; and
- Adoptium, Ubuntu, Docker, and Jenkins package repositories during provisioning.

Only expose SSH and Jenkins to trusted networks. Prefer a LAN, management VLAN, or VPN.
Use a DHCP reservation or static address and configure DNS consistently.

Docker-published container ports require separate attention. Docker documents that
published ports can bypass ordinary UFW or firewalld rules. Do not assume that an active
UFW policy alone protects Testcontainers' mapped ports. Review Docker's packet-filtering
guidance, inspect the `DOCKER-USER` chain, restrict the ESXi port group to trusted
networks, and never publish the Docker API on an unsecured TCP socket.

Useful read-only checks after Docker is installed are:

```bash
sudo iptables -S DOCKER-USER
sudo ss -lntp
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

Design and test any `DOCKER-USER` rules against the actual management interface and
trusted CIDR before making them persistent. An incorrect blanket rule can disconnect
Jenkins, SSH, or container traffic.

### VMware guest integration and time

Install `open-vm-tools` and `chrony`. Accurate time is important for timeout-sensitive,
event-ordering, and bi-temporal tests. Do not suspend or pause the VM during a test run.

Nested virtualization is not required. Docker Engine inside the Linux guest uses Linux
namespaces and cgroups; it does not require ESXi nested virtualization.

## Ubuntu base installation

Ubuntu Server 24.04 LTS is recommended because it has mature Java, Docker, Node, and
browser tooling support and a long maintenance window. Canonical's server documentation
uses Ubuntu's `docker.io` package for Docker, while Docker also explicitly supports
Ubuntu Noble 24.04 LTS on `amd64` through its separate upstream package repository. The
Docker section below distinguishes those two supply and support paths instead of treating
them as interchangeable.

After installing Ubuntu, update the system and install the base packages:

```bash
sudo apt update
sudo apt full-upgrade -y

sudo apt install -y \
  git curl wget ca-certificates gnupg unzip jq xz-utils \
  lsof xvfb open-vm-tools chrony build-essential tmux fontconfig
```

Enable time synchronization and VMware guest tools:

```bash
sudo systemctl enable --now chrony
sudo systemctl enable --now open-vm-tools
```

## Docker Engine and Testcontainers

### Packaging decision for Ubuntu 24.04

Use Ubuntu's `docker.io` package from the Noble archives as the default for this VM. This
is a project decision based on Canonical's Ubuntu Server guidance, the current package on
the actual VM, and PeeGeeQ's Docker API requirement. It is not a claim that Ubuntu's
package is universally better than Docker's upstream package.

| Consideration | Ubuntu `docker.io` | Docker upstream `docker-ce` |
|---|---|---|
| Repository | Ubuntu Noble archives already trusted by the VM | Third-party `download.docker.com` apt repository |
| Vendor guidance | Used by Canonical's Docker-for-sysadmins guide | Used by Docker's Ubuntu installation guide |
| Support boundary | Ubuntu packaging; `docker.io` is in Universe, with the security-maintenance caveat below | Docker packaging and support, not Canonical support |
| Update behavior | Canonical's Docker stack SRU exception permits major-version updates in an LTS | Docker's stable release channel and package cadence |
| Rootless on 24.04 | Not documented by Canonical as a Noble package path | Documented by Docker upstream, but requires a different design |
| PeeGeeQ status | Selected, subject to the API and test gates below | Valid fallback if a required feature or support contract demands it |

Canonical's wording needs to be read carefully. Its Docker guide says that the Ubuntu
`docker.io` package is supported by Canonical, but Ubuntu's general package-management
documentation classifies Universe packages as community-supported and says Canonical's
security-maintenance commitment for Universe requires Ubuntu Pro ESM Apps. The package
is currently receiving Noble updates, including a build in `noble-security`, but that
current fact must not be generalized into a stronger support entitlement. Check the
organization's Ubuntu Pro or commercial support terms rather than assuming coverage.

Canonical's container-stack update policy also says that Docker is intentionally kept
close to upstream in supported Ubuntu releases. Major-version updates can enter an LTS,
and Canonical describes the associated package QA as basic rather than exhaustive. This
is useful for avoiding a permanently old daemon, but it means Docker upgrades on this CI
worker require a maintenance window and PeeGeeQ verification.

Docker's upstream repository remains legitimate and explicitly supports Ubuntu 24.04.
Choose it only as a recorded packaging decision—for example, when Docker vendor support,
a specific upstream package, or rootless Docker on Noble is required. Do not mix
`docker.io`/`containerd` packages with `docker-ce`/`containerd.io` packages on one host.

Canonical also publishes a Docker snap, but its server guide does not use that path. The
snap is not the PeeGeeQ baseline and would require its own Testcontainers socket,
confinement, networking, and full-suite validation.

### Verified state of `ubu24-cicd`

The following facts were verified over SSH during provisioning on 2026-08-23. They are a
snapshot, not permanent package guarantees:

- host `ubu24-cicd` is Ubuntu 24.04 Noble on `amd64`, kernel `6.8.0-138-generic`, with
  4 vCPUs and 15 GiB RAM;
- the 20 GB virtual disk uses a 1.8 GB `/boot` partition and an 18.2 GB LVM root volume;
  after provisioning, Temurin installation, and diagnostics, `/` has approximately
  9 GB available;
- `/swapfile` is active, persistent through `/etc/fstab`, owned by `root`, mode `0600`,
  and configured with `vm.swappiness=10` in `/etc/sysctl.d/99-peegeeq-ci.conf`;
- `docker.io` `29.1.3-0ubuntu3~24.04.2` is installed from
  `noble-updates/universe` and `noble-security/universe`;
- `docker-compose-v2` `2.40.3+ds1-0ubuntu1~24.04.1` and `docker-buildx`
  `0.30.1-0ubuntu1~24.04.1` are installed;
- Docker is enabled and active, listens only on `/run/docker.sock`, and passed a
  privileged `hello-world` container smoke test;
- `docker-ce` has no candidate because Docker's third-party repository is not enabled;
- Ubuntu OpenJDK 21.0.11 is installed for the Jenkins controller, Ubuntu OpenJDK JDK
  25.0.3 remains installed but is not the selected build JDK, Eclipse Temurin
  25.0.4.1 is installed for PeeGeeQ builds, and Apache Maven 3.9.16 is installed;
- Jenkins LTS 2.568.2 is installed from the official `debian-stable` repository, enabled,
  active, and runs as the non-root `jenkins` account on Java 21;
- the first-run wizard is complete, the named administrator `mraysmit` exists, and the
  Pipeline, Git, GitHub Branch Source, Credentials Binding, JUnit, and Pipeline Graph
  View plugins are installed;
- UFW is active with default-deny inbound policy, and the operator confirmed that port
  8080 is allowed only from the Windows workstation at `192.168.137.29`;
- the `jenkins` account is a member of the root-equivalent `docker` group, the restarted
  service inherited that supplementary group, and an operator-run `hello-world` smoke
  test succeeded as `jenkins`;
- `/var/lib/jenkins/.m2/toolchains.xml` selects
  `/usr/lib/jvm/temurin-25-jdk-amd64` for JDK 25 builds and is readable only by the
  `jenkins` account;
- Git 2.43.0 and Xvfb are installed; host-level Node and npm are intentionally absent
  because the Maven frontend plugin provisions the project-pinned versions; and
- the Ubuntu Pro client is installed, but this VM is not attached to a Pro subscription.

### 2026-08-23 JDK and memory diagnostic

The first Jenkins build reached the rebuild stage but failed while compiling
`peegeeq-test-support`. Reproducing that reactor slice outside Jenkins with Ubuntu
OpenJDK 25 produced a native `SIGSEGV` in `libjvm.so`, in
`InstanceKlass::find_method_index`. Maven's forked compiler surfaced only a blank
compilation failure in the Jenkins log. The same source revision compiled on Windows
with another JDK distribution.

During the attempted Temurin installation, two downloaded package archives later read
with different checksums. Direct I/O returned the correct on-disk bytes while ordinary
buffered reads returned altered bytes. The same discrepancy affected the in-memory page
cache for `/var/lib/dpkg/status`: the direct on-disk copy was valid and byte-identical
to `status-old`, while the cached copy contained changed dependency text. This proves
that the guest experienced transient memory or page-cache corruption. It also means the
earlier JVM crash cannot be attributed conclusively to Ubuntu's OpenJDK build.

The ESXi host exposes no IPMI sensor data, and the reviewed VMkernel log contained only
normal machine-check initialization messages, not a reported ECC, MCA, memory-controller,
or page-retirement event. Restarting the ESXi host cleared the observed guest corruption.
After restart:

- cached and direct reads of `/var/lib/dpkg/status` matched;
- a time-boxed user-space `memtester` screen exercised 4 GiB through most patterns
  without reporting an error, but was stopped before completing and is not a full
  physical-memory test;
- the verified Temurin package installed successfully and passed `dpkg --verify`; and
- the exact failed reactor slice, pinned to commit
  `1454c4e718dee96267e54b0a6fd36504d9bbd484`, passed with Temurin using
  `mvn clean install -DskipTests -pl :peegeeq-test-support -am`.

The worker is therefore usable only on a provisional basis until an offline physical
memory test can be scheduled. Any future checksum mismatch, impossible package-manager
parse error, unexplained JVM native crash, or nondeterministic compiler output is a stop
condition: preserve evidence, stop Jenkins, and test the ESXi host's physical memory.

Re-run these checks immediately before installation because repository candidates can
change:

```bash
. /etc/os-release
printf 'OS=%s %s CODENAME=%s ARCH=%s\n' \
  "$NAME" "$VERSION_ID" "$VERSION_CODENAME" "$(dpkg --print-architecture)"
apt-cache policy docker.io docker-compose-v2 docker-buildx containerd runc docker-ce
pro status
```

### PeeGeeQ Docker API compatibility gate

PeeGeeQ currently pins Testcontainers 2.0.2. That release changed its default Docker API
version to 1.44. Docker Engine 24.0 exposes at most API 1.43, so the original
`docker.io` 24.0.7 package from the base Noble release is not an acceptable unmodified
baseline for this project. Docker Engine 25.0 introduced API 1.44; the currently offered
Noble Docker 29.1 candidate supports API versions from 1.44 through 1.52.

Therefore, do not install from an offline, pinned, or stale Noble source that resolves
`docker.io` to 24.0.7. Enable and update the normal `noble-updates` and `noble-security`
sources and require Docker Engine 25 or newer. Do not hide a version mismatch by forcing
`DOCKER_API_VERSION`; preserve the normal client/daemon negotiation and prove the actual
combination with PeeGeeQ's targeted tests.

### Install the Ubuntu-packaged Docker stack

On the current clean VM, install the Ubuntu packages:

```bash
sudo apt update
apt-cache policy docker.io docker-compose-v2 docker-buildx containerd runc
sudo apt install -y docker.io docker-compose-v2 docker-buildx
sudo systemctl enable --now docker
```

`docker.io` is sufficient for Testcontainers. `docker-compose-v2` is included because
PeeGeeQ contains maintained Compose files using the `docker compose` command, and
`docker-buildx` supports the repository's Dockerfile build workflows. Do not install the
legacy `docker-compose` 1.29 package for those workflows.

If this ceases to be a clean VM, inspect installed packages and Docker state before
changing package families:

```bash
dpkg -l | awk '$1 == "ii" && $2 ~ /^(docker|containerd|runc)/ {print $2, $3}'
if command -v docker >/dev/null 2>&1; then
  sudo docker info
else
  echo 'Docker CLI is not installed.'
fi
```

Do not copy an uninstall command from either vendor's guide without first deciding how
existing images, containers, volumes, and `/var/lib/docker` will be preserved.

Validate the daemon and the required API range before granting Jenkins access:

```bash
sudo systemctl status docker --no-pager
sudo docker version
sudo docker run --rm hello-world
sudo docker version --format \
  'client={{.Client.Version}} client_api={{.Client.APIVersion}} server={{.Server.Version}} server_api={{.Server.APIVersion}} server_min_api={{.Server.MinAPIVersion}}'
docker compose version
docker buildx version
```

The installed server must accept API 1.44. Record the package version and Docker version
output with the VM provisioning notes.

### Selected Docker security model

Use standard rootful Docker on the dedicated Ubuntu build VM. The daemon runs as `root`;
Jenkins remains a non-root Linux service account and reaches it through the root-owned
Unix socket at `/var/run/docker.sock`:

```text
Jenkins process (jenkins, non-root)
    -> /var/run/docker.sock (docker group)
    -> Docker daemon (root)
    -> Testcontainers containers
```

Jenkins does not need `sudo` and must not run as `root`, but membership in the `docker`
group gives its jobs root-equivalent authority over the build VM. A pipeline can ask
Docker to mount host paths, start privileged containers, or otherwise control the host.
The Docker-enabled agent is a privileged, trusted-code boundary, not a sandbox.

For the preferred two-VM design, install Docker only on the build agent. Do not install
Docker on the controller, add the controller's `jenkins` account to the `docker` group,
or give controller jobs access to an agent Docker socket.

Grant access only to the account that runs builds. For an all-in-one VM whose service
account is `jenkins`:

```bash
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

For a separate agent, replace `jenkins` with its dedicated agent account and restart the
agent service or login session so the new supplementary group is loaded. Never work
around socket permissions with `chmod 666 /var/run/docker.sock`.

Verify both the group assignment and actual Docker use as the build account:

```bash
getent group docker
id jenkins
test -S /var/run/docker.sock
stat -c '%A %U %G %n' /var/run/docker.sock
sudo -u jenkins -H docker version
sudo -u jenkins -H docker run --rm hello-world
```

These checks prove access, not isolation. Any account that can control this socket is an
administrator of the build VM. Do not expose the socket over a bind mount, and never
expose an unauthenticated Docker API on TCP port 2375. A TLS-protected remote API on 2376
is also unnecessary for this local-agent design; keep the daemon on its Unix socket.

### Why rootless Docker is not the Ubuntu 24.04 baseline

Docker upstream supports a rootless mode in which both the daemon and containers run in
a non-root user's context. It normally uses a per-user systemd service, a socket such as
`/run/user/<uid>/docker.sock`, `DOCKER_HOST`, user namespaces, and rootless-specific
networking, storage, and cgroup behavior.

Canonical's current Ubuntu Server guide says its packaged rootless Docker path starts in
Ubuntu 26.04. It does not document that path for Ubuntu 24.04, and the current Noble
`docker.io` file list does not include the rootless setup scripts shown in Canonical's
26.04 instructions. Therefore, rootless Docker is not the Canonical-aligned Noble
baseline for this VM.

Using Docker upstream's rootless packages on Noble remains technically possible, but it
changes both the package-support decision and the Testcontainers environment.
Testcontainers can discover a per-user daemon through `DOCKER_HOST`; that does not prove
PeeGeeQ's full suite has no rootless-specific assumptions. If policy forbids a root
daemon, create a separate upstream-rootless agent design and validate targeted container
tests plus the owner-run full regression gate before adopting it.

Do not add `jenkins` to the standard Docker group and then describe the result as
rootless; Docker group access remains root-equivalent.

### Testcontainers operation and Linux correction

Testcontainers says it is actively tested against recent Docker versions on Linux and
automatically discovers the normal local Docker environment. On this baseline, leave
`DOCKER_HOST` unset and keep Ryuk enabled so it removes containers and networks after
builds.

The repository file
`peegeeq-test-support/src/test/resources/testcontainers.properties` currently forces:

```properties
docker.client.strategy=org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy
```

That provider is for the Windows Docker named pipe, not a Linux agent using
`/var/run/docker.sock`. Before treating this worker as a complete-suite runner, remove
the forced `docker.client.strategy` line and allow Testcontainers to detect Linux Docker.
Do not replace it with another hard-coded platform strategy without a demonstrated need.

Pre-pull the PostgreSQL image used by PeeGeeQ to reduce the first-run delay:

```bash
sudo -u jenkins -H docker pull postgres:15.13-alpine3.20
```

Monitor storage using `docker system df`; do not use broad automatic pruning without
understanding which caches and images it removes. Docker-published ports may bypass UFW
rules, so apply the networking controls above and inspect Docker's `DOCKER-USER` chain.

### Docker update gate

Do not allow a Docker major-version update to become an unobserved change underneath a
90-minute CI gate. For each Docker stack update:

1. record the old and proposed `apt-cache policy` and `docker version` output;
2. update a cloned worker or take a recoverable ESXi snapshot after confirming backups;
3. run `hello-world` and the Docker API compatibility check;
4. rebuild the affected PeeGeeQ reactor slice;
5. run the smallest relevant Testcontainers integration scope and inspect test counts;
6. run the owner-controlled full regression gate before declaring the updated worker a
   release-gate environment; and
7. remove temporary snapshots after the upgrade is accepted.

Do not hold vulnerable Docker packages indefinitely merely to avoid this gate. Control
when upgrades are promoted, test them, and retain a reproducible previous VM template.

## Java installation

Two Java roles should be kept conceptually separate:

- Jenkins itself runs on a Jenkins-supported Java runtime. Java 21 is the conservative
  controller choice.
- PeeGeeQ builds with JDK 25, as required by the root Maven configuration and toolchain.

Use Ubuntu OpenJDK 21 for Jenkins and Eclipse Temurin 25 for PeeGeeQ builds. Temurin is
installed from Eclipse Adoptium's signed apt repository. This is a deliberate
third-party repository choice, and its signing key is scoped to that repository rather
than trusted globally.

```bash
sudo apt update
sudo apt install -y ca-certificates curl fontconfig gpg openjdk-21-jre

sudo install -d -m 0755 /etc/apt/keyrings

key_file="$(mktemp)"
trap 'rm -f "$key_file"' EXIT

curl -fsSL \
  https://packages.adoptium.net/artifactory/api/gpg/key/public \
  -o "$key_file"

fingerprint="$(
  gpg --show-keys --with-colons "$key_file" |
  awk -F: '$1 == "fpr" { print $10; exit }'
)"

test "$fingerprint" = "3B04D753C9050D9A5D343F39843C48A565F8F04B"

gpg --dearmor < "$key_file" |
  sudo tee /etc/apt/keyrings/adoptium.gpg >/dev/null

sudo chmod 0644 /etc/apt/keyrings/adoptium.gpg

adoptium_codename="$(awk -F= '$1 == "VERSION_CODENAME" { print $2 }' /etc/os-release)"

echo \
  "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${adoptium_codename} main" |
  sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null

sudo apt update
apt-cache policy temurin-25-jdk
sudo apt install -y temurin-25-jdk

sudo update-alternatives --set java /usr/lib/jvm/temurin-25-jdk-amd64/bin/java
sudo update-alternatives --set javac /usr/lib/jvm/temurin-25-jdk-amd64/bin/javac

java -version
javac -version
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version
dpkg --verify temurin-25-jdk
```

The first two version checks should report Temurin Java 25. The explicit Java 21 check
proves the controller runtime remains available. Confirm the selected JDK location:

```bash
dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
```

Create the build account's Maven toolchain file at `~/.m2/toolchains.xml`. On an
all-in-one VM this normally means `/var/lib/jenkins/.m2/toolchains.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>25</version>
    </provides>
    <configuration>
      <jdkHome>/usr/lib/jvm/temurin-25-jdk-amd64</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

Replace `jdkHome` with the actual location reported on the VM. Set ownership correctly:

```bash
sudo chown -R jenkins:jenkins /var/lib/jenkins/.m2
```

The pipeline should set `JAVA_HOME` to `/usr/lib/jvm/temurin-25-jdk-amd64` for Maven
commands. This does not change the Java runtime used by the already-running Jenkins
service.

## Maven installation

PeeGeeQ requires Maven 3.8 or newer. The Ubuntu 24.04 package currently supplies Maven
3.8.7, but Apache Maven identifies 3.9.16 as the current stable release and recommends it
for all users. Install that exact binary under `/opt`, verify its published SHA-512
checksum before extraction, and expose it through stable symlinks:

```bash
curl -fL \
  https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz \
  -o /tmp/apache-maven-3.9.16-bin.tar.gz

echo "831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6  /tmp/apache-maven-3.9.16-bin.tar.gz" \
  | sha512sum --check -

sudo tar -xzf /tmp/apache-maven-3.9.16-bin.tar.gz -C /opt
sudo ln -sfn /opt/apache-maven-3.9.16 /opt/maven
sudo ln -sfn /opt/maven/bin/mvn /usr/local/bin/mvn
rm /tmp/apache-maven-3.9.16-bin.tar.gz
```

The checksum command must report `OK`. Do not extract an archive that fails this check.
When upgrading Maven, obtain the new version and checksum from Apache, validate it, and
change `/opt/maven` only after the new installation is complete.

Official downloads and installation guidance:

- <https://maven.apache.org/download.cgi>
- <https://maven.apache.org/install.html>

Verify as the build account:

```bash
sudo -u jenkins -H mvn -version
```

The output used for builds should show JDK 25 after the pipeline environment is applied.

## Node, npm, and Playwright

The UI Maven modules pin Node 22.12.0 and npm 10.2.4 through the frontend Maven plugin.
The plugin can provision those versions inside the build, so a host-level Node
installation is not required for Maven or Jenkins execution. Browser operating-system
packages still require a one-time administrative installation on the VM. Browser binaries
do not: install them as the `jenkins` account so Playwright writes to that account's cache.

The Maven plugin creates module-local Node, npm, and npx launchers during the rebuild. A
Git archive or workspace materialization can leave the npm/npx launchers without their
executable bit on Linux. The repository pipeline therefore restores and verifies only the
owner executable bit after the rebuild, then invokes the pinned Playwright CLI with the
module-local Node runtime:

```bash
for frontend in peegeeq-management-ui peegeeq-utilities-ui; do
    chmod u+x "$frontend/node/npm" "$frontend/node/npx"
    test -x "$frontend/node/npm"
    test -x "$frontend/node/npx"
done

peegeeq-management-ui/node/node \
  peegeeq-management-ui/node_modules/@playwright/test/cli.js \
  install chromium
```

These commands are non-root and run inside Jenkins after Maven has provisioned the pinned
frontend runtime and dependencies. Do not replace them with a system `npm` or `npx`
assumption: the verified VM does not need host-level Node. If browser operating-system
dependencies change, an administrator must update the VM separately; the pipeline must
not elevate itself.

Both UI end-to-end runners currently request headed browsers. Ubuntu Server has no real
display, so the complete Maven invocation must run under Xvfb:

```bash
CI=true xvfb-run -a mvn clean test -Pall-tests
```

The `CI=true` environment also enables the UI suites' CI-specific behavior. Playwright's
official CI documentation describes both dependency installation and Xvfb operation:

<https://playwright.dev/docs/ci>

## Installing Jenkins LTS

Install Jenkins natively from the official Jenkins LTS apt repository. Native Jenkins is
recommended for this use case because it avoids adding another Docker layer around a
build that already depends heavily on Docker and Testcontainers. Jenkins itself runs as
the non-root `jenkins` systemd service; it is not installed in a container and must not
be reconfigured to run as `root`. Docker is installed on the host for Testcontainers,
and the explicitly granted Docker socket access is what makes builds privileged.

Install Java 21 first, then add the Jenkins LTS repository:

```bash
sudo wget -O /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key

echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | \
  sudo tee /etc/apt/sources.list.d/jenkins.list >/dev/null

sudo apt update
sudo apt install -y jenkins

sudo install -d -m 0755 /etc/systemd/system/jenkins.service.d
printf '%s\n' \
  '[Service]' \
  'Environment="JENKINS_JAVA_CMD=/usr/lib/jvm/java-21-openjdk-amd64/bin/java"' | \
  sudo tee /etc/systemd/system/jenkins.service.d/java.conf >/dev/null

sudo systemctl daemon-reload
sudo systemctl enable jenkins
sudo systemctl restart jenkins
```

The system-wide `java` alternative remains Temurin JDK 25 for PeeGeeQ command-line
builds. The systemd drop-in pins only the Jenkins controller to Java 21. Current Jenkins
LTS releases support both Java 21 and Java 25, but keeping the controller runtime
explicit prevents a future alternatives change from silently changing Jenkins.

Check the service and retrieve the initial administrator password:

```bash
sudo systemctl status jenkins
sudo journalctl -u jenkins --no-pager -n 100
ps -C java -o user=,args= | grep '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
sudo sed -n '1p' /var/lib/jenkins/secrets/initialAdminPassword
```

Open `http://<jenkins-host>:8080` from a trusted workstation and complete the setup
wizard. The official installation guide is:

<https://www.jenkins.io/doc/book/installing/linux/>

### Recommended Jenkins plugins

Start with a minimal plugin set:

- Pipeline;
- Git;
- Credentials Binding;
- JUnit;
- Pipeline Graph View; and
- the branch-source plugin for the Git hosting system in use.

Add notification plugins only when there is a defined recipient and escalation policy.
Keep Jenkins core and plugins patched, and remove plugins that are no longer required.

### Controller security

- Do not expose port 8080 directly to the public Internet.
- Restrict access with a firewall, VPN, or management VLAN.
- Put Jenkins behind a TLS reverse proxy if it is shared beyond a trusted LAN.
- Disable anonymous administration and use least-privilege accounts.
- In the preferred two-VM design, do not install Docker on the controller, add its
  `jenkins` account to the `docker` group, or schedule project builds there.
- Store Git credentials and tokens in Jenkins Credentials, never in the repository or
  pipeline source.
- Use a read-only deploy key where Jenkins only needs to clone the repository.
- Restrict Jenkins administration, **Job/Configure**, **Run/Replay**, agent
  configuration, and in-process scripting permissions to trusted administrators.
  Jenkins documents that these capabilities can lead to arbitrary command execution.
- Treat anyone who can change or replay a pipeline running on the Docker-enabled agent
  as an administrator of that agent VM, because Docker socket control is root-equivalent.
- Do not run unreviewed pull requests or builds from untrusted forks on the privileged
  agent, especially when credentials are available. Use a separately isolated worker
  without sensitive credentials if such builds must run.
- Never expose `/var/run/docker.sock` through Jenkins, a container bind mount, or an
  unauthenticated TCP listener.
- Back up `/var/lib/jenkins` and test restoration periodically.

### Separate build agent configuration

For the preferred two-VM architecture:

1. Install JDK 21 or another controller-compatible agent runtime on the worker.
2. Install Temurin JDK 25, Maven, Docker, Node, Xvfb, Playwright, Git, and the base
   packages on the worker.
3. Create a dedicated agent account and add only that account to the Docker group,
   recording that this makes the account root-equivalent on the worker.
4. Add the node under **Manage Jenkins > Nodes**.
5. Give it the label `peegeeq-linux`.
6. Set its remote root to a large data-disk path such as `/srv/jenkins-agent`.
7. Connect it using the approved SSH or inbound-agent method.
8. Set controller executors to zero so the controller cannot run project builds.
9. Keep production credentials, unrelated sensitive workloads, and untrusted pipelines
   off the worker; make the worker reproducible and replaceable.

The all-in-one design can label the built-in node `peegeeq-linux` instead. In that
design, anyone who can configure or replay its Docker-enabled pipelines must be treated
as an administrator of the entire VM, including its Jenkins controller state.

## CI job strategy

The complete suite should not run after every commit. Use progressively broader jobs:

| Job | Trigger | Command | Expected role |
|---|---|---|---|
| Core | Every trusted branch push and trusted pull request | `mvn test` | Fast feedback, approximately 30 seconds |
| Smoke | Every trusted branch push and trusted pull request | `mvn test -Psmoke-tests` | Very fast end-to-end feedback |
| Integration | Main branch, trusted selected pull requests, or manual | `mvn test -Pintegration-tests` | Testcontainers and real infrastructure, approximately 60 minutes for all modules |
| Full regression | Nightly, manual, protected-main push, or release gate | `mvn clean test -Pall-tests` | Every tag and module, approximately 90 minutes |
| Performance | Manual or scheduled quiet period | `mvn test -Pperformance-tests -pl :<module>` | Controlled load and throughput testing |
| Untagged audit | Nightly or trusted pull request | `mvn test -Puntagged-tests` | Detect tests missing a supported tag |

The full regression profile is the single whole-repository test guarantee. Core,
integration, smoke, and module-scoped runs must never be reported as proof that the
entire repository passed.

The trigger policy must also respect the agent's privilege boundary. Only reviewed,
trusted revisions may run on the Docker-enabled agent. A public or otherwise untrusted
fork must not be allowed to execute its own Jenkinsfile on this agent or receive Jenkins
credentials. If untrusted contribution testing is required, give it a separate isolated
worker and a deliberately reduced job without the privileged Docker socket or secrets.

For implementation changes, retain the repository's required rebuild-before-test rule.
The safest broad CI equivalent is a complete rebuild before a regression run:

```bash
mvn clean install -DskipTests
```

For targeted CI jobs, rebuild the changed module and upstream reactor slice using
`-pl :<changed-module> -am`, then run the smallest relevant tagged test scope. The
`-DskipTests` option is only for that rebuild/install phase; it is not a substitute for
the following verification phase.

## Repository Jenkinsfile

The canonical pipeline is stored in [`../../Jenkinsfile`](../../Jenkinsfile) so it is versioned
and reviewed with the code. Jenkins documents this approach at:

<https://www.jenkins.io/doc/book/pipeline/jenkinsfile/>

The job uses fixed `TEST_SUITE` and `ALL_TESTS_START_MODULE` choices rather than accepting
an arbitrary shell command:

| Choice | Maven test command | Intended use |
|---|---|---|
| `core` | `mvn test` | Default fast feedback; runs the root POM's default core selection |
| `smoke` | `mvn test -Psmoke-tests` | Explicit ultra-fast end-to-end checks |
| `integration` | `mvn test -Pintegration-tests` | Explicit Testcontainers integration selection |
| `untagged` | `mvn test -Puntagged-tests` | Audit for tests missing a supported tag |
| `all` | `xvfb-run -a mvn clean test -Pall-tests` | Explicit approximately 90-minute regression gate |

`ALL_TESTS_START_MODULE` applies only when `TEST_SUITE=all`. Its values are `beginning`
plus each reactor module in build order, from `peegeeq-test-support` through
`peegeeq-utilities-ui`. `beginning` runs the entire full regression and is mandatory for
final release acceptance. Selecting a module adds Maven `-rf :<module>` so diagnosis can
continue at the last failed module. A resumed run proves only the selected module and the
reactor tail; it is never a whole-repository result. After the tail is green, rerun `all`
from `beginning` for the release gate.

Every selection first runs `mvn clean install -DskipTests`, which compiles tests and
installs the complete reactor before verification. This complete rebuild also prepares
upstream artifacts before a resumed regression. The pipeline then restores executable
permission on the Maven-provisioned npm/npx launchers and installs Playwright Chromium
with the pinned module-local Node runtime. It validates Temurin JDK 25, the Maven
toolchain, Docker socket access, Docker API compatibility, disk, memory, and swap.
It allows only one build at a time, enforces a 150-minute overall timeout, keeps ten build
records and five artifact sets, publishes JUnit XML, archives logs and Playwright
diagnostics, and deletes only its allocated Jenkins workspace during final cleanup.

The `all` choice is never the default. Configure scheduled or protected-branch jobs to
select it deliberately; ordinary first runs and SCM-triggered runs use `core`. Scheduled
and protected release jobs must also select `ALL_TESTS_START_MODULE=beginning`.

The `bash -o pipefail` wrapper is important. Without it, a command piped through `tee`
can expose the logger's exit status instead of Maven's failure status.

The environment stage treats host-level Node as optional because the Maven frontend
plugin provides the pinned runtime. Required build and test commands must not have
their errors suppressed. The Docker checks deliberately expect the selected rootful,
local-socket baseline; a rootless design would need a different, reviewed environment
contract rather than silently setting `DOCKER_HOST` in this pipeline.

### Verified full-gate snapshot — 2026-08-25

Jenkins build #36 ran `TEST_SUITE=all` and `ALL_TESTS_START_MODULE=beginning` at
`e8d07e53bf779660a68c47a1df94ec190c3c6665`. It finished `SUCCESS`; the checked-out
revision matched origin and the final worktree was clean. Published results were:

- Java: 4,022 tests, zero failures, zero errors, zero skipped;
- management UI unit tests: 95/95;
- management UI Playwright: 481 passed plus one flaky retry, 482 total;
- utilities UI unit tests: 836/836; and
- utilities UI Playwright: 91/91.

The management Playwright retry was `queue-updates-sse.spec.ts:274`. It is registered as
`NOT REPRODUCED` in the test-integrity remediation plan; the successful retry must not be
reported as a deterministic fix.

Jenkins' JUnit publisher records test failures, trends, and history:

<https://www.jenkins.io/doc/pipeline/steps/junit/>

## Jenkins job configuration

Create a multibranch pipeline when the Git host supports branch and pull-request
discovery. Point it at the repository's `Jenkinsfile` and configure a webhook so Jenkins
does not need frequent polling.

Recommended policies:

- do not allow concurrent full-regression runs on the same agent;
- allow only reviewed, trusted repository revisions on the Docker-enabled agent;
- restrict **Job/Configure**, **Run/Replay**, agent configuration, and credential access
  to trusted administrators;
- do not expose credentials to builds from untrusted forks;
- retain approximately 20 recent builds, adjusted for available disk space;
- use a 150-minute initial timeout for the 90-minute suite;
- send notifications when status changes rather than for every successful build;
- archive reports on both success and failure;
- keep a nightly full-suite schedule outside normal developer activity; and
- require an explicit manual action for performance tests and release gates where
  appropriate.

A separate, fast pull-request Jenkinsfile or parameterized pipeline can run only the
core and smoke profiles. The nightly job should use the complete profile.

## Initial validation sequence

Before creating an automated nightly schedule, validate the worker interactively as the
same user that Jenkins will use.

Create a log directory and confirm tools:

```bash
mkdir -p logs
java -version
mvn -version
dpkg-query -W -f='${binary:Package} ${Version}\n' \
  docker.io docker-compose-v2 docker-buildx
apt-cache policy docker.io docker-compose-v2 docker-buildx containerd runc
id
test -S /var/run/docker.sock
stat -c '%A %U %G %n' /var/run/docker.sock
test -z "${DOCKER_HOST:-}"
docker version
docker version --format \
  'client={{.Client.Version}} client_api={{.Client.APIVersion}} server={{.Server.Version}} server_api={{.Server.APIVersion}} server_min_api={{.Server.MinAPIVersion}}'
docker context show
docker info --format '{{json .SecurityOptions}}'
docker compose version
docker buildx version
docker ps
```

For the selected standard Docker baseline, the build account should be in the `docker`
group, the local socket should be owned by `root:docker`, `DOCKER_HOST` should be unset,
the installed engine must accept Docker API 1.44, and the client should reach the host
daemon without `sudo`. Record the Ubuntu package origin, package versions, and Docker
API output in the provisioning notes. Remember that success confirms root-equivalent
Docker authority; it does not demonstrate that the account is sandboxed.

Also inspect host exposure from an administrative shell:

```bash
sudo ss -lntp
sudo iptables -S DOCKER-USER
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

Confirm that no Docker API is listening on TCP and that the ESXi network boundary and
Docker firewall policy match the trusted network design before enabling scheduled jobs.

Warm build caches and compile the complete reactor:

```bash
bash -o pipefail -c \
  'mvn clean install -DskipTests 2>&1 | tee logs/bootstrap-build.log'
```

Then progress through the test profiles:

```bash
bash -o pipefail -c \
  'mvn test 2>&1 | tee logs/core-tests.log'
```

```bash
bash -o pipefail -c \
  'mvn test -Psmoke-tests 2>&1 | tee logs/smoke-tests.log'
```

```bash
bash -o pipefail -c \
  'mvn test -Pintegration-tests -pl :peegeeq-db \
  2>&1 | tee logs/peegeeq-db-integration.log'
```

Only after those phases are healthy, run the complete gate:

```bash
bash -o pipefail -c \
  'CI=true xvfb-run -a mvn clean test -Pall-tests \
  2>&1 | tee logs/all-tests-$(date +%F-%H%M).log'
```

Read the saved Maven logs and inspect the per-class `Tests run:` summaries. `BUILD
SUCCESS` alone does not prove that the expected tests executed.

In Jenkins, the equivalent release invocation is `TEST_SUITE=all` with
`ALL_TESTS_START_MODULE=beginning`. If a module fails, a manual diagnostic run may select
that module to avoid repeating the already-green prefix. Once the failing module and
remaining tail pass, run again from `beginning`; do not promote a resumed result as the
full gate.

Use the current Maven profile commands rather than assuming older helper scripts remain
current. The authoritative profile definitions are the root `pom.xml` and the PeeGeeQ
test-command guide.

## Build outputs to retain

At minimum, retain:

- `logs/**`;
- `**/target/surefire-reports/*.xml`;
- `**/target/failsafe-reports/*.xml`;
- `**/playwright-report/**`; and
- `**/test-results/**`.

JUnit XML should be published through Jenkins rather than only archived, because Jenkins
can display individual failures and historical trends. Playwright HTML reports,
screenshots, traces, and videos should be archived for failed UI runs.

Do not archive every Maven dependency or Docker layer in Jenkins. Maven's local
repository and Docker's layer store already provide local caches on a persistent agent.

## Operating and maintenance guidance

### Snapshots and backups

- Take a clean ESXi snapshot or template after initial provisioning if it helps recover
  the worker quickly.
- Do not leave VMware snapshots active during performance tests; write amplification can
  distort results significantly.
- VMware snapshots are not backups.
- Back up Jenkins configuration, credentials, job definitions, and secrets from
  `/var/lib/jenkins` using an approved backup mechanism.
- A build agent should be reproducible from this guide or automation rather than treated
  as the only copy of important state.

### Disk management

Monitor:

```bash
df -h
docker system df
du -sh /var/lib/jenkins/.m2 /var/lib/jenkins/workspace 2>/dev/null
```

Configure Jenkins build retention and periodically inspect old workspaces, Playwright
videos, Maven snapshots, and unused Docker layers. Prefer measured cleanup policies over
unconditional destructive pruning.

### Service health

Useful diagnostics include:

```bash
sudo systemctl status jenkins docker containerd
sudo journalctl -u jenkins --since today
sudo journalctl -u docker --since today
```

Restart services only after preserving the failing build's reports and logs.

### Docker privilege and network review

Recheck the `docker` group's membership after account, Jenkins, or agent changes. Every
listed member has root-equivalent control of the worker. On a separate-controller
installation, the controller must not appear in this group and must not have a Docker
socket.

After Docker upgrades or firewall changes, repeat the socket, listening-port, published
container-port, and `DOCKER-USER` checks from the initial validation section. Do not
enable an unauthenticated Docker TCP endpoint, weaken the socket to world-writable, or
blindly prune Docker state as a routine maintenance shortcut.

Review package origin and update policy at the same time:

```bash
apt-cache policy docker.io docker-compose-v2 docker-buildx containerd runc docker-ce
pro status
```

The selected baseline should resolve to Ubuntu's Noble archives and should not show an
installed `docker-ce` package. Decide explicitly whether Ubuntu Pro ESM Apps coverage is
required for this Universe package. Because Canonical's Docker SRU policy permits major
updates with basic package QA, promote Docker updates through the Docker update gate in
this guide rather than changing the release-gate worker during an active test window.

### Performance-test discipline

Performance tests executed inside a VM are useful for regressions when the environment
is held constant; they are not automatically equivalent to bare-metal production
capacity.

For repeatable measurements:

- reserve the worker's CPU and memory;
- avoid ESXi CPU overcommit;
- remove active VM snapshots;
- use the same host and datastore between comparison runs;
- prevent backup jobs and other heavy workloads from overlapping;
- record VM sizing and Git revision with each result; and
- compare against a baseline from the same environment.

## Reducing the 90-minute runtime

First obtain several stable runs on one worker and determine which modules and test
classes dominate elapsed time. Do not increase parallelism blindly.

The most promising next step is to clone the prepared build-agent template and shard
independent module groups across two or three agents. Separate VMs provide stronger
isolation for Docker, ports, CPU, memory, and workspaces than adding threads to one Maven
reactor.

A possible future progression is:

1. one reliable full-suite agent;
2. separate fast pull-request and nightly full-suite jobs;
3. a second cloned agent for UI or selected module groups;
4. measured sharding based on Jenkins timing history; and
5. aggregation of JUnit and Playwright results into one gate.

Any sharded design must still prove that every module and test tag executed. It must not
replace the complete regression guarantee with a collection of partial runs whose
coverage is unknown.

## Provisioning checklist

### ESXi and Ubuntu

- [ ] Create the controller and worker VMs, or one all-in-one VM.
- [ ] Place build storage on a fast datastore.
- [ ] Install Ubuntu Server 24.04 LTS.
- [ ] Configure VMXNET3, PVSCSI, DNS, and a stable IP address.
- [ ] Install and enable `open-vm-tools` and `chrony`.
- [ ] Restrict inbound access to the trusted LAN, management VLAN, or VPN.

### Build stack

- [ ] Confirm the VM is Ubuntu 24.04 Noble `amd64` and re-run `apt-cache policy` rather
      than relying on the dated package snapshot in this guide.
- [ ] Record the package-source decision: Ubuntu `docker.io` is the default; upstream
      `docker-ce` is an explicit alternative and must not be mixed with it.
- [ ] Confirm the `docker.io` candidate is Docker Engine 25 or newer and can accept
      Testcontainers 2.0.2's Docker API 1.44 requirement.
- [ ] Select and record the standard rootful Docker baseline; do not describe Docker
      group access as rootless or unprivileged.
- [ ] Install `docker.io`, `docker-compose-v2`, and `docker-buildx` from the Ubuntu Noble
      archives; do not install the legacy `docker-compose` package.
- [ ] Record whether Ubuntu Pro ESM Apps coverage is required and available for the
      Universe `docker.io` package.
- [ ] Record that Canonical's Docker update exception permits major-version changes and
      route each Docker stack update through the documented test gate.
- [ ] Keep Jenkins or the agent service non-root; do not run it with `sudo` or as `root`.
- [ ] Give only the trusted build account Docker group access and record that it is
      root-equivalent on the worker.
- [ ] Verify the local socket is `root:docker` and Docker works as the build account.
- [ ] Confirm `DOCKER_HOST` is unset and no Docker API is exposed on TCP.
- [ ] Review Docker-published ports, the ESXi network boundary, and the `DOCKER-USER`
      chain instead of relying on UFW alone.
- [ ] If policy requires rootless Docker, stop and validate a separate rootless design
      and full PeeGeeQ test gate instead of mixing rootful and rootless instructions.
- [ ] Pull `postgres:15.13-alpine3.20`.
- [ ] Install Java 21 for Jenkins.
- [ ] Install Temurin JDK 25 for PeeGeeQ.
- [ ] Configure Maven Toolchains for JDK 25.
- [ ] Install Maven 3.9.16 and verify its published SHA-512 checksum.
- [ ] Let Maven provision the pinned Node 22.12.0 and npm 10.2.4 runtimes; a host Node
      installation is optional, not a pipeline prerequisite.
- [ ] Install `xvfb`, `lsof`, and Playwright operating-system dependencies on the VM.
- [ ] Verify the pipeline restores owner executable permission on both module-local
      npm/npx launchers and installs Chromium as the Jenkins account without elevation.

### Repository compatibility

- [ ] Remove the Windows-only Testcontainers named-pipe strategy.
- [ ] Confirm the Linux Docker socket is detected.
- [ ] Record Docker package, client, server, maximum API, and minimum API versions.
- [ ] Confirm the daemon accepts API 1.44 without forcing `DOCKER_API_VERSION`.
- [ ] Confirm headed Playwright runs successfully through `xvfb-run`.
- [ ] Confirm the browser binary is available in the Jenkins account's Playwright cache.
- [ ] Verify core tests and inspect their per-class counts.
- [ ] Verify smoke tests and inspect their per-class counts.
- [ ] Verify a targeted Testcontainers integration module.
- [ ] Run the full `all-tests` gate and inspect saved logs and reports.

### Jenkins

- [ ] Install Jenkins LTS from the official repository.
- [ ] Confirm Jenkins is a native non-root systemd service, not a Jenkins container.
- [ ] Complete the setup wizard and create named administrator accounts.
- [ ] Install only the required plugins.
- [ ] Store SCM credentials in Jenkins Credentials.
- [ ] Create and label the `peegeeq-linux` node.
- [ ] On a two-VM installation, keep Docker off the controller and set controller
      executors to zero.
- [ ] Restrict **Job/Configure**, **Run/Replay**, agent configuration, scripting, and
      credential permissions to trusted administrators.
- [ ] Prevent unreviewed forks and untrusted revisions from running on the Docker-enabled
      agent or receiving credentials.
- [ ] Configure a multibranch pipeline or repository webhook.
- [ ] Publish JUnit results and archive Playwright diagnostics.
- [ ] Configure build retention, timeout, and concurrency controls.
- [ ] Confirm `ALL_TESTS_START_MODULE` is ignored outside `all`, resumes only at fixed
      reactor module choices, and defaults to `beginning`.
- [ ] Treat module-resumed builds as diagnostics and require a final `all` run from
      `beginning` before release acceptance.
- [ ] Schedule the full suite nightly or invoke it as an explicit release gate.
- [ ] Configure and test Jenkins backups.

## Authoritative external references

- Canonical Docker for system administrators: <https://ubuntu.com/server/docs/how-to/containers/docker-for-system-admins/>
- Canonical package-management and Universe support guidance: <https://ubuntu.com/server/docs/how-to/software/package-management/>
- Canonical container-stack update policy: <https://documentation.ubuntu.com/project/SRU/reference/exception-Docker-Updates/>
- Ubuntu Noble `docker.io` package: <https://packages.ubuntu.com/en/noble/docker.io>
- Ubuntu Pro ESM Apps coverage: <https://documentation.ubuntu.com/pro-client/en/latest/tutorials/security-with-pro/>
- Jenkins Linux installation: <https://www.jenkins.io/doc/book/installing/linux/>
- Jenkins Pipeline and Jenkinsfile: <https://www.jenkins.io/doc/book/pipeline/jenkinsfile/>
- Jenkins JUnit publishing: <https://www.jenkins.io/doc/pipeline/steps/junit/>
- Jenkins authorization permissions: <https://www.jenkins.io/doc/book/security/access-control/permissions/>
- Docker Engine on Ubuntu: <https://docs.docker.com/engine/install/ubuntu/>
- Docker Linux post-installation: <https://docs.docker.com/engine/install/linux-postinstall/>
- Docker security: <https://docs.docker.com/engine/security/>
- Docker rootless mode: <https://docs.docker.com/engine/security/rootless/>
- Docker rootless limitations: <https://docs.docker.com/engine/security/rootless/troubleshoot/#known-limitations>
- Docker packet filtering and firewalls: <https://docs.docker.com/engine/network/packet-filtering-firewalls/>
- Docker Engine API version matrix: <https://docs.docker.com/reference/api/engine/>
- Testcontainers supported Docker environments: <https://java.testcontainers.org/supported_docker_environment/>
- Testcontainers 2.0.2 release: <https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.2>
- Playwright CI and Xvfb: <https://playwright.dev/docs/ci>
- Eclipse Adoptium Linux installation: <https://adoptium.net/installation/linux/>
- Apache Maven downloads: <https://maven.apache.org/download.cgi>
- Ubuntu release lifecycle: <https://ubuntu.com/about/release-cycle>
