pipeline {
    agent { label 'peegeeq-linux' }

    parameters {
        choice(
            name: 'TEST_SUITE',
            choices: ['core', 'smoke', 'integration', 'untagged', 'performance', 'partitioned-release', 'all'],
            description: 'Performance runs retain raw and structured statistics. The all suite is the explicit approximately 90-minute regression gate.'
        )
        choice(
            name: 'ALL_TESTS_START_MODULE',
            choices: [
                'beginning',
                'peegeeq-test-support',
                'peegeeq-api',
                'peegeeq-db',
                'peegeeq-outbox',
                'peegeeq-native',
                'peegeeq-bitemporal',
                'peegeeq-runtime',
                'peegeeq-rest',
                'peegeeq-rest-client',
                'peegeeq-service-manager',
                'peegeeq-pg-sidecar',
                'peegeeq-examples',
                'peegeeq-benchmarking',
                'peegeeq-migrations',
                'peegeeq-openapi',
                'peegeeq-integration-tests',
                'peegeeq-coverage-report',
                'peegeeq-management-ui',
                'peegeeq-utilities-ui'
            ],
            description: 'For the all suite, resume at the last failed Maven module. Use beginning for the final full gate.'
        )
    }

    environment {
        JAVA_HOME = '/usr/lib/jvm/temurin-25-jdk-amd64'
        MAVEN_HOME = '/opt/maven'
        PATH = "/usr/lib/jvm/temurin-25-jdk-amd64/bin:/opt/maven/bin:${env.PATH}"
        CI = 'true'
    }

    options {
        skipDefaultCheckout(true)
        timeout(time: 150, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Environment') {
            steps {
                sh '''
                    set -eu

                    java -version
                    javac -version
                    mvn -version
                    git --version
                    id

                    test "$(readlink -f "$(command -v java)")" = \
                      '/usr/lib/jvm/temurin-25-jdk-amd64/bin/java'
                    test "$(readlink -f "$(command -v javac)")" = \
                      '/usr/lib/jvm/temurin-25-jdk-amd64/bin/javac'
                    test -r "$HOME/.m2/toolchains.xml"
                    grep -F '<version>25</version>' "$HOME/.m2/toolchains.xml"
                    grep -F '<jdkHome>/usr/lib/jvm/temurin-25-jdk-amd64</jdkHome>' \
                      "$HOME/.m2/toolchains.xml"

                    id -nG | tr ' ' '\n' | grep -qx docker
                    test -S /var/run/docker.sock
                    test -z "${DOCKER_HOST:-}"
                    stat -c '%A %U %G %n' /var/run/docker.sock
                    docker version --format \
                      'client={{.Client.Version}} client_api={{.Client.APIVersion}} server={{.Server.Version}} server_api={{.Server.APIVersion}} server_min_api={{.Server.MinAPIVersion}}'
                    docker context show
                    docker info --format 'driver={{.Driver}} security={{json .SecurityOptions}}'

                    df -h /
                    free -h
                    swapon --show
                '''
                script {
                    // The UI stage's container needs this group to use the mounted Docker socket.
                    env.DOCKER_SOCKET_GID = sh(returnStdout: true, script: 'stat -c %g /var/run/docker.sock').trim()
                }
            }
        }

        stage('Rebuild') {
            steps {
                sh '''
                    set -eu
                    mkdir -p logs
                    bash -o pipefail -c \
                      'mvn --no-transfer-progress clean install -DskipTests \
                      2>&1 | tee logs/rebuild.log'

                    for frontend in peegeeq-management-ui peegeeq-utilities-ui; do
                        chmod u+x "$frontend/node/npm" "$frontend/node/npx"
                        test -x "$frontend/node/npm"
                        test -x "$frontend/node/npx"
                        # Reports must belong to the selected test invocation, not the rebuild.
                        rm -f "$frontend/target/ui-reports/vitest.xml" \
                          "$frontend/target/ui-reports/playwright.xml"
                    done

                    # The browser suites of both UI modules run on Firefox.
                    peegeeq-management-ui/node/node \
                      peegeeq-management-ui/node_modules/@playwright/test/cli.js \
                      install firefox
                '''
            }
        }

        stage('Core tests') {
            when {
                expression { params.TEST_SUITE == 'core' }
            }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test \
                      2>&1 | tee logs/core-tests.log'
                '''
            }
        }

        stage('Smoke tests') {
            when {
                expression { params.TEST_SUITE == 'smoke' }
            }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test -Psmoke-tests \
                      2>&1 | tee logs/smoke-tests.log'
                '''
            }
        }

        stage('Integration tests') {
            when {
                expression { params.TEST_SUITE == 'integration' }
            }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test -Pintegration-tests \
                      2>&1 | tee logs/integration-tests.log'
                '''
            }
        }

        stage('Untagged audit') {
            when {
                expression { params.TEST_SUITE == 'untagged' }
            }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test -Puntagged-tests \
                      2>&1 | tee logs/untagged-tests.log'
                '''
            }
        }

        stage('Performance tests') {
            when {
                expression { params.TEST_SUITE == 'performance' }
            }
            steps {
                sh '''
                    set -eu
                    mkdir -p logs performance-results
                    {
                        date --iso-8601=seconds
                        git rev-parse HEAD
                        uname -a
                        nproc
                        free -h
                        df -h .
                        docker version --format '{{.Server.Version}}'
                    } > performance-results/host-baseline.log

                    vmstat 60 > performance-results/host-vmstat.log &
                    metrics_pid=$!
                    finish_metrics() {
                        rc=$?
                        trap - EXIT
                        kill "$metrics_pid" 2>/dev/null || true
                        wait "$metrics_pid" 2>/dev/null || true
                        free -h >> performance-results/host-vmstat.log
                        df -h . >> performance-results/host-vmstat.log
                        exit "$rc"
                    }
                    trap finish_metrics EXIT

                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test -Pperformance-tests \
                      -pl :peegeeq-benchmarking -am \
                      2>&1 | tee logs/performance-tests.log'
                '''
            }
        }

        stage('Partitioned consumption release gate') {
            when {
                expression { params.TEST_SUITE == 'partitioned-release' }
            }
            steps {
                sh '''
                    set -eu
                    mkdir -p logs performance-results
                    {
                        date --iso-8601=seconds
                        git rev-parse HEAD
                        uname -a
                        nproc
                        free -h
                        df -h .
                        docker version --format '{{.Server.Version}}'
                    } > performance-results/host-baseline.log

                    vmstat 60 > performance-results/host-vmstat.log &
                    metrics_pid=$!
                    finish_metrics() {
                        rc=$?
                        trap - EXIT
                        kill "$metrics_pid" 2>/dev/null || true
                        wait "$metrics_pid" 2>/dev/null || true
                        free -h >> performance-results/host-vmstat.log
                        df -h . >> performance-results/host-vmstat.log
                        exit "$rc"
                    }
                    trap finish_metrics EXIT

                    bash -o pipefail -c \
                      'mvn --no-transfer-progress test -Pperformance-tests \
                      -pl :peegeeq-benchmarking \
                      -Dtest=PartitionedConsumptionReleaseGate \
                      -Dpeegeeq.task6.duration.seconds=3600 \
                      -Dpeegeeq.task6.message.rate=200 \
                      -Dpeegeeq.task6.partition.count=16 \
                      -Dpeegeeq.task6.groups.per.tenant=2 \
                      -Dpeegeeq.task6.pool.size=4 \
                      -Dtest.timeout.default=100m \
                      -Dtest.timeout.method=95m \
                      2>&1 | tee logs/partitioned-release.log'
                '''
            }
        }

        // The full regression runs in two stages so that the browser tests are a stage of their
        // own. scripts/ci/regression-stages.mjs derives each stage's modules from the root pom.xml.
        stage('Full regression: Java modules') {
            when {
                expression { params.TEST_SUITE == 'all' }
            }
            steps {
                sh '''
                    set -eu
                    selection="$(peegeeq-management-ui/node/node \
                      scripts/ci/regression-stages.mjs java "$ALL_TESTS_START_MODULE")"
                    case "$selection" in
                        skip)
                            echo "No Java module is at or after $ALL_TESTS_START_MODULE. This stage runs no test."
                            exit 0
                            ;;
                        -Pall-tests*)
                            echo "Maven arguments of the Java stage: $selection"
                            ;;
                        *)
                            echo "Unexpected stage selection: $selection" >&2
                            exit 1
                            ;;
                    esac

                    bash -o pipefail -c \
                      "mvn --no-transfer-progress clean test $selection \
                      2>&1 | tee logs/all-tests-java.log"
                '''
            }
        }

        stage('Full regression: UI modules') {
            when {
                expression { params.TEST_SUITE == 'all' }
            }
            // The browser tests run in the Playwright image, as the Playwright CI guide describes.
            // A browser on the host cancels its page loads when another job creates or removes a
            // Docker network, because that changes the network interfaces of the host. A container
            // has its own interfaces. The image tag must equal the @playwright/test version.
            // The stage also runs Maven and starts a PostgreSQL container, so the JDK, Maven, the
            // local Maven repository, and the Docker socket of the host are mounted.
            agent {
                docker {
                    image 'mcr.microsoft.com/playwright:v1.60.0-noble'
                    reuseNode true
                    args "--ipc=host --init " +
                         "-e HOME=/var/lib/jenkins -e npm_config_cache=/tmp/npm-cache " +
                         // HOME holds only the Maven repository and is not writable in the
                         // container. The browser needs writable profile directories to start.
                         "-e XDG_CONFIG_HOME=/tmp/xdg-config -e XDG_CACHE_HOME=/tmp/xdg-cache " +
                         // Jenkins runs the container as its own user id, which the image does
                         // not know. Node fails to read the current user without this entry.
                         "-v /etc/passwd:/etc/passwd:ro " +
                         "-v /var/lib/jenkins/.m2:/var/lib/jenkins/.m2 " +
                         "-v /usr/lib/jvm/temurin-25-jdk-amd64:/usr/lib/jvm/temurin-25-jdk-amd64:ro " +
                         "-v /opt/maven:/opt/maven:ro " +
                         "-v /var/run/docker.sock:/var/run/docker.sock " +
                         "--group-add ${env.DOCKER_SOCKET_GID}"
                }
            }
            steps {
                sh '''
                    set -eu
                    # The Docker Pipeline plugin does not pass the PATH of the environment block
                    # into the container.
                    export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
                    java -version
                    mvn -version

                    selection="$(peegeeq-management-ui/node/node \
                      scripts/ci/regression-stages.mjs ui "$ALL_TESTS_START_MODULE")"
                    case "$selection" in
                        skip)
                            echo "No UI module is at or after $ALL_TESTS_START_MODULE. This stage runs no test."
                            exit 0
                            ;;
                        -Pall-tests*)
                            echo "Maven arguments of the UI stage: $selection"
                            ;;
                        *)
                            echo "Unexpected stage selection: $selection" >&2
                            exit 1
                            ;;
                    esac

                    bash -o pipefail -c \
                      "xvfb-run -a mvn --no-transfer-progress clean test $selection \
                      2>&1 | tee logs/all-tests-ui.log"
                '''
            }
        }
    }

    post {
        always {
            script {
                // Performance evidence is immutable: retain successful, failed, and aborted runs.
                if (params.TEST_SUITE in ['performance', 'partitioned-release']) {
                    currentBuild.keepLog = true
                }
                // A missing expected report is a build failure, even if another suite published.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh '''
                        peegeeq-management-ui/node/node scripts/ci/check-ui-reports.mjs \
                          "$TEST_SUITE" "$ALL_TESTS_START_MODULE"
                    '''
                }
                try {
                    junit(
                        testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml,peegeeq-*-ui/target/ui-reports/vitest.xml,peegeeq-*-ui/target/ui-reports/playwright.xml',
                        allowEmptyResults: false
                    )
                } finally {
                    archiveArtifacts(
                        artifacts: 'logs/**,performance-results/**,**/target/performance-results/**,**/target/surefire-reports/**,**/target/failsafe-reports/**,**/playwright-report/**,**/test-results/**,**/target/ui-reports/*.xml',
                        allowEmptyArchive: true
                    )
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
