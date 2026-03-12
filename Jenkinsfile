// ════════════════════════════════════════════════════════════════
// Dev Pipeline — amazon-microservices repo
//
// Lives at: amazon-microservices/Jenkinsfile
//
// What it does:
//   1. Detects which services changed (skip unchanged ones)
//   2. Builds changed services with Maven
//   3. Runs unit tests
//   4. Builds Docker images
//   5. Pushes to local registry (localhost:5000)
//   6. On success → triggers the automation pipeline
//      passing the IMAGE_TAG so automation tests the exact
//      version that was just built
//
// Learning focus for Day 1-3:
//   - How pipeline stages work
//   - How parallel stages are declared
//   - How environment variables flow through stages
//   - How one pipeline triggers another
// ════════════════════════════════════════════════════════════════

pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()          // Never 2 builds at once
        timestamps()
        ansiColor('xterm')
    }

    // ── GitHub Webhook trigger ───────────────────────────────────
    // GitHub sends a POST to https://<ngrok-url>/github-webhook/
    // whenever someone pushes to the repo.
    // ngrok forwards that POST to Jenkins running locally.
    //
    // For this to work:
    //   1. ngrok must be running (it's in docker-compose.yml)
    //   2. Jenkins URL must be set to the ngrok public URL
    //      (setup-webhooks.sh does this automatically)
    //   3. The GitHub repo must have a webhook pointing at ngrok URL
    //      (Jenkins registers this automatically via githubPush plugin)
    triggers {
        githubPush()    // Fires instantly when GitHub webhook POST arrives
    }

    environment {
        // All images tagged with git commit SHA (first 8 chars)
        // WHY? 'latest' tag causes stale deployments — you can't tell
        // which version is running. SHA = immutable, traceable.
        IMAGE_TAG   = "${env.GIT_COMMIT?.take(8) ?: 'dev'}"
        REGISTRY    = "localhost:5000"
        PROJECT     = "amazon"
        MAVEN_OPTS  = "-Xmx256m -XX:+UseG1GC"  // Cap Maven heap
    }

    stages {

        // ── Stage 1: Checkout & Change Detection ─────────────────
        stage('Checkout') {
            steps {
                checkout scm   // scm = whatever SCM config is on this job

                script {
                    // Detect which services have changed since last build
                    // WHY? Building all 6 services on every push wastes time.
                    // If only user-service changed, only build user-service.
                    def changedFiles = sh(
                        script: "git diff --name-only HEAD~1 HEAD 2>/dev/null || echo 'ALL'",
                        returnStdout: true
                    ).trim()

                    echo "Changed files:\n${changedFiles}"

                    // Determine which services need rebuilding
                    env.BUILD_USER_SERVICE    = changedFiles.contains('user-service')    || changedFiles == 'ALL' ? 'true' : 'false'
                    env.BUILD_PRODUCT_SERVICE = changedFiles.contains('product-service') || changedFiles == 'ALL' ? 'true' : 'false'
                    env.BUILD_ORDER_SERVICE   = changedFiles.contains('order-service')   || changedFiles == 'ALL' ? 'true' : 'false'
                    env.BUILD_PAYMENT_SERVICE = changedFiles.contains('payment-service') || changedFiles == 'ALL' ? 'true' : 'false'
                    env.BUILD_NOTIFICATION    = changedFiles.contains('notification-service') || changedFiles == 'ALL' ? 'true' : 'false'
                    env.BUILD_GATEWAY         = changedFiles.contains('api-gateway')     || changedFiles == 'ALL' ? 'true' : 'false'

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
        // WHY parallel? Each Maven build is independent.
        // Running them in parallel cuts build time from ~12min to ~3min.
        //
        // ⚠️  COMMON ISSUE YOU'LL HIT:
        // If you set numExecutors=1 in jenkins.yaml, parallel stages
        // actually run SEQUENTIALLY (Jenkins queues them on 1 executor).
        // Set numExecutors=2 to truly parallelize. Watch your RAM.
        stage('Build & Unit Tests') {
            parallel {

                stage('user-service') {
                    when { expression { env.BUILD_USER_SERVICE == 'true' } }
                    steps {
                        dir('user-service') {
                            sh '''
                                echo "Building user-service..."
                                mvn clean verify \
                                  --no-transfer-progress \
                                  -Dmaven.test.failure.ignore=false
                            '''
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
        // WHY sequential (not parallel)?
        // Parallel Docker builds on a laptop cause OOM.
        // Each `docker build` loads layers into memory simultaneously.
        // Sequential is slower but stable. Parallel is ~30% faster but risky.
        //
        // ⚠️  COMMON ISSUE YOU'LL HIT:
        // "Cannot connect to Docker daemon" — means Jenkins can't reach
        // Docker socket. Fix: check the volume mount in docker-compose.yml
        // and ensure jenkins user is in docker group (see Dockerfile).
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
                            echo "🐳 Building image: ${REGISTRY}/${PROJECT}/${svc.name}:${IMAGE_TAG}"
                            sh """
                                docker build \
                                  -t ${REGISTRY}/${PROJECT}/${svc.name}:${IMAGE_TAG} \
                                  -t ${REGISTRY}/${PROJECT}/${svc.name}:latest \
                                  ./${svc.name}

                                echo "Image size:"
                                docker image ls ${REGISTRY}/${PROJECT}/${svc.name}:${IMAGE_TAG} \
                                  --format "  {{.Repository}}:{{.Tag}} → {{.Size}}"
                            """
                        } else {
                            echo "⏭️  Skipping ${svc.name} (no changes)"
                        }
                    }
                }
            }
        }

        // ── Stage 4: Push to Local Registry ──────────────────────
        // WHY a local registry instead of Docker Hub or ECR?
        // - No internet needed (works offline)
        // - No authentication headaches for local dev
        // - Instant push (localhost, not internet)
        // - Same concept as ECR — just swap the URL when going to AWS
        //
        // ⚠️  COMMON ISSUE YOU'LL HIT:
        // "http: server gave HTTP response to HTTPS client"
        // Fix: add localhost:5000 to Docker's insecure registries.
        // Docker Desktop → Settings → Docker Engine → add:
        //   "insecure-registries": ["localhost:5000"]
        stage('Push to Local Registry') {
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
                            sh """
                                docker push ${REGISTRY}/${PROJECT}/${svc.name}:${IMAGE_TAG}
                                docker push ${REGISTRY}/${PROJECT}/${svc.name}:latest
                                echo "✅ Pushed ${svc.name}:${IMAGE_TAG}"
                            """
                        }
                    }

                    echo """
All images pushed. Browse them at: http://localhost:5001
Tag used: ${IMAGE_TAG}
"""
                }
            }
        }

        // ── Stage 5: Trigger Automation Pipeline ─────────────────
        // This is the KEY handoff between dev and automation pipelines.
        //
        // HOW IT WORKS:
        //   1. Dev pipeline finishes building + pushing image tag X
        //   2. This stage calls Jenkins API to start automation pipeline
        //   3. It passes IMAGE_TAG=X as a parameter
        //   4. Automation pipeline uses that exact image — not 'latest'
        //      (using 'latest' here would be a bug — a newer push could
        //       overwrite 'latest' between build and test)
        //
        // WHY `wait: false`?
        //   The dev pipeline doesn't wait for tests to finish.
        //   Dev pipeline = "did the code build?" (fast, ~5 min)
        //   Automation pipeline = "does the code work?" (slow, ~20 min)
        //   They run independently. Dev pipeline result ≠ test result.
        //   If you want dev pipeline to wait: change to `wait: true`
        //   (but then your dev pipeline takes 25 min per push)
        stage('Trigger Automation Tests') {
            when {
                // Only trigger automation from main/develop branches
                // Don't waste resources testing feature branches on every push
                anyOf {
                    branch 'main'
                    branch 'develop'
                }
            }
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
                    // Trigger the automation pipeline with the image tag
                    // 'wait: false' = fire and forget (dev pipeline finishes immediately)
                    // 'wait: true'  = dev pipeline waits for automation to complete
                    build(
                        job: 'automation-tests',    // Must match the automation pipeline job name
                        parameters: [
                            string(name: 'IMAGE_TAG',    value: env.IMAGE_TAG),
                            string(name: 'TRIGGERED_BY', value: env.JOB_NAME),
                            string(name: 'GIT_COMMIT',   value: env.GIT_COMMIT ?: 'unknown'),
                            string(name: 'BRANCH',       value: env.BRANCH_NAME ?: 'unknown'),
                        ],
                        wait: false,           // Don't block dev pipeline
                        propagate: false       // Don't fail dev pipeline if tests fail
                    )

                    echo "✅ Automation pipeline triggered. Check: http://localhost:8090/job/automation-tests"
                }
            }
        }
    }

    // ── Post-build actions ───────────────────────────────────────
    post {
        success {
            echo """
╔══════════════════════════════════════════════╗
║  ✅ Dev Pipeline SUCCESS                      ║
║  Image Tag: ${IMAGE_TAG.padRight(32)}║
║  Registry:  http://localhost:5001             ║
║  Automation pipeline triggered (check UI)     ║
╚══════════════════════════════════════════════╝"""
        }

        failure {
            echo """
╔══════════════════════════════════════════════╗
║  ❌ Dev Pipeline FAILED                       ║
║  Automation pipeline NOT triggered            ║
║  Fix the build error above, then push again   ║
╚══════════════════════════════════════════════╝"""
        }

        always {
            // Clean workspace to save disk space
            // Remove compiled .class files, test reports etc.
            // Images remain in registry — pipeline artifacts are separate
            cleanWs(
                cleanWhenSuccess: true,
                cleanWhenFailure: true,
                deleteDirs: true
            )
        }
    }
}
