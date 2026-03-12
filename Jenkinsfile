// ════════════════════════════════════════════════════════════════
// Dev Pipeline — amazon-microservices
// Pushes images to Docker Hub: rabtab/amazon-<service>:<git-sha>
// ════════════════════════════════════════════════════════════════

pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timestamps()
        ansiColor('xterm')
    }

    triggers {
        githubPush()
    }

    environment {
        IMAGE_TAG    = "${env.GIT_COMMIT?.take(8) ?: 'dev'}"
        DOCKERHUB_USER = "rabtab"
        PROJECT      = "amazon"
        MAVEN_OPTS   = "-Xmx256m -XX:+UseG1GC"
    }

    stages {

        // ── Stage 1: Checkout & Change Detection ─────────────────
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    def changedFiles = sh(
                        script: "git diff --name-only HEAD~1 HEAD 2>/dev/null || echo 'ALL'",
                        returnStdout: true
                    ).trim()

                    // If empty (first commit) or no previous commit, build everything
                    def buildAll = !changedFiles || changedFiles.isEmpty() || changedFiles == 'ALL'
                    if (buildAll) changedFiles = 'ALL'

                    echo "Changed files:\n${changedFiles}"

                    env.BUILD_USER_SERVICE    = buildAll || changedFiles.contains('user-service')         ? 'true' : 'false'
                    env.BUILD_PRODUCT_SERVICE = buildAll || changedFiles.contains('product-service')      ? 'true' : 'false'
                    env.BUILD_ORDER_SERVICE   = buildAll || changedFiles.contains('order-service')        ? 'true' : 'false'
                    env.BUILD_PAYMENT_SERVICE = buildAll || changedFiles.contains('payment-service')      ? 'true' : 'false'
                    env.BUILD_NOTIFICATION    = buildAll || changedFiles.contains('notification-service') ? 'true' : 'false'
                    env.BUILD_GATEWAY         = buildAll || changedFiles.contains('api-gateway')          ? 'true' : 'false'

                    echo """
Services to build:
  user-service:         ${env.BUILD_USER_SERVICE}
  product-service:      ${env.BUILD_PRODUCT_SERVICE}
  order-service:        ${env.BUILD_ORDER_SERVICE}
  payment-service:      ${env.BUILD_PAYMENT_SERVICE}
  notification-service: ${env.BUILD_NOTIFICATION}
  api-gateway:          ${env.BUILD_GATEWAY}
"""
                }
            }
        }

        // ── Stage 2: Build & Unit Tests ──────────────────────────
        stage('Build & Unit Tests') {
            parallel {

                stage('user-service') {
                    when { expression { env.BUILD_USER_SERVICE == 'true' } }
                    steps {
                        dir('user-service') {
                            sh 'mvn clean verify --no-transfer-progress -Dmaven.test.failure.ignore=false'
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true,
                                  testResults: 'user-service/target/surefire-reports/TEST-*.xml'
                        }
                    }
                }

                stage('product-service') {
                    when { expression { env.BUILD_PRODUCT_SERVICE == 'true' } }
                    steps {
                        dir('product-service') {
                            sh 'mvn clean verify --no-transfer-progress'
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true,
                                  testResults: 'product-service/target/surefire-reports/TEST-*.xml'
                        }
                    }
                }

                stage('order-service') {
                    when { expression { env.BUILD_ORDER_SERVICE == 'true' } }
                    steps {
                        dir('order-service') {
                            sh 'mvn clean verify --no-transfer-progress'
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true,
                                  testResults: 'order-service/target/surefire-reports/TEST-*.xml'
                        }
                    }
                }

                stage('payment-service') {
                    when { expression { env.BUILD_PAYMENT_SERVICE == 'true' } }
                    steps {
                        dir('payment-service') {
                            sh 'mvn clean verify --no-transfer-progress'
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true,
                                  testResults: 'payment-service/target/surefire-reports/TEST-*.xml'
                        }
                    }
                }

                stage('notification-service') {
                    when { expression { env.BUILD_NOTIFICATION == 'true' } }
                    steps {
                        dir('notification-service') {
                            sh 'mvn clean verify --no-transfer-progress'
                        }
                    }
                }

                stage('api-gateway') {
                    when { expression { env.BUILD_GATEWAY == 'true' } }
                    steps {
                        dir('api-gateway') {
                            sh 'mvn clean verify --no-transfer-progress'
                        }
                    }
                }

            } // end parallel
        }

        // ── Stage 3: Docker Build ────────────────────────────────
        // Images tagged as: rabtab/amazon-user-service:abc12345
        // Sequential builds — parallel would OOM on 8GB Mac
        stage('Docker Build') {
            steps {
                script {
                    def services = [
                        [name: 'user-service',         build: env.BUILD_USER_SERVICE],
                        [name: 'product-service',      build: env.BUILD_PRODUCT_SERVICE],
                        [name: 'order-service',        build: env.BUILD_ORDER_SERVICE],
                        [name: 'payment-service',      build: env.BUILD_PAYMENT_SERVICE],
                        [name: 'notification-service', build: env.BUILD_NOTIFICATION],
                        [name: 'api-gateway',          build: env.BUILD_GATEWAY],
                    ]

                    services.each { svc ->
                        if (svc.build == 'true') {
                            def imageName = "${DOCKERHUB_USER}/${PROJECT}-${svc.name}"
                            echo "🐳 Building: ${imageName}:${IMAGE_TAG}"
                            sh """
                                docker build \
                                  -t ${imageName}:${IMAGE_TAG} \
                                  -t ${imageName}:latest \
                                  ./${svc.name}

                                docker image ls ${imageName}:${IMAGE_TAG} \
                                  --format "  {{.Repository}}:{{.Tag}} → {{.Size}}"
                            """
                        } else {
                            echo "⏭️  Skipping ${svc.name} (no changes)"
                        }
                    }
                }
            }
        }

        // ── Stage 4: Push to Docker Hub ──────────────────────────
        // Logs in with stored credential, pushes all built images,
        // then logs out immediately (never leave credentials active)
        stage('Push to Docker Hub') {
            steps {
                script {
                    def services = [
                        [name: 'user-service',         build: env.BUILD_USER_SERVICE],
                        [name: 'product-service',      build: env.BUILD_PRODUCT_SERVICE],
                        [name: 'order-service',        build: env.BUILD_ORDER_SERVICE],
                        [name: 'payment-service',      build: env.BUILD_PAYMENT_SERVICE],
                        [name: 'notification-service', build: env.BUILD_NOTIFICATION],
                        [name: 'api-gateway',          build: env.BUILD_GATEWAY],
                    ]

                    // withCredentials injects username+password safely
                    // They are masked in logs — you will never see the actual token
                    withCredentials([usernamePassword(
                        credentialsId: 'dockerhub-creds',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'

                        services.each { svc ->
                            if (svc.build == 'true') {
                                def imageName = "${DOCKERHUB_USER}/${PROJECT}-${svc.name}"
                                sh """
                                    docker push ${imageName}:${IMAGE_TAG}
                                    docker push ${imageName}:latest
                                    echo "✅ Pushed ${imageName}:${IMAGE_TAG}"
                                """
                            }
                        }

                        sh 'docker logout'
                    }

                    echo """
Images on Docker Hub:
  https://hub.docker.com/u/${DOCKERHUB_USER}
  Tag: ${IMAGE_TAG}
"""
                }
            }
        }

        // ── Stage 5: Trigger Automation Pipeline ─────────────────
        stage('Trigger Automation Tests') {
            steps {
                script {
                    echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🚀 Triggering Automation Pipeline
   Image Tag: ${IMAGE_TAG}
   Branch:    ${env.BRANCH_NAME}
   Commit:    ${env.GIT_COMMIT?.take(8)}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""
                    build(
                        job: 'automation-tests',
                        parameters: [
                            string(name: 'IMAGE_TAG',    value: env.IMAGE_TAG),
                            string(name: 'TRIGGERED_BY', value: env.JOB_NAME),
                            string(name: 'GIT_COMMIT',   value: env.GIT_COMMIT ?: 'unknown'),
                            string(name: 'BRANCH',       value: env.BRANCH_NAME ?: 'unknown'),
                        ],
                        wait: false,
                        propagate: false
                    )
                    echo "✅ Automation pipeline triggered"
                }
            }
        }
    }

    post {
        success {
            echo """
╔══════════════════════════════════════════════════════╗
║  ✅ Dev Pipeline SUCCESS                              ║
║  Images pushed to: hub.docker.com/u/rabtab            ║
║  Tag: ${IMAGE_TAG}
╚══════════════════════════════════════════════════════╝"""
        }
        failure {
            echo "❌ Dev Pipeline FAILED — automation pipeline NOT triggered"
        }
        always {
            cleanWs(cleanWhenSuccess: true, cleanWhenFailure: true, deleteDirs: true)
        }
    }
}