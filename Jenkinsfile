pipeline {
    agent { label 'peegeeq-linux' }

    parameters {
        choice(
            name: 'TEST_SUITE',
            choices: ['core', 'smoke', 'integration', 'untagged', 'all'],
            description: 'The all suite is the explicit approximately 90-minute regression gate.'
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

        stage('Full regression') {
            when {
                expression { params.TEST_SUITE == 'all' }
            }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'xvfb-run -a mvn --no-transfer-progress clean test -Pall-tests \
                      2>&1 | tee logs/all-tests.log'
                '''
            }
        }
    }

    post {
        always {
            junit(
                testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml',
                allowEmptyResults: true
            )
            archiveArtifacts(
                artifacts: 'logs/**,**/playwright-report/**,**/test-results/**',
                allowEmptyArchive: true
            )
        }
        cleanup {
            deleteDir()
        }
    }
}
