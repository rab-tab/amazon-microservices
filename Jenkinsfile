// ════════════════════════════════════════════════════════════════
// Dev Pipeline — amazon-microservices
// Pushes images to ECR: <account-id>.dkr.ecr.<region>.amazonaws.com/amazon-<service>:<git-sha>
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
        IMAGE_TAG      = "${env.GIT_COMMIT?.take(8) ?: 'dev'}"
        AWS_REGION     = "us-east-1"                 // match the region ECR repos were created in
        AWS_ACCOUNT_ID = "978185568053"               // ⚠️ replace with your actual account ID
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        PROJECT        = "amazon"
        MAVEN_OPTS     = "-Xmx256m -XX:+UseG1GC"
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
        // Images tagged as: <ECR_REGISTRY>/amazon-user-service:abc12345
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
                            def imageName = "${ECR_REGISTRY}/${PROJECT}-${svc.name}"
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

        // ── Stage 4: Push to ECR ──────────────────────────────────
        // Authenticates using the jenkins-ecr IAM user's keys (stored in
        // Jenkins as an "AWS Credentials" entry, id: aws-ecr-creds), pushes
        // all built images, then logs out immediately (never leave the
        // Docker credential store holding a live auth token).
        // Note: the ECR token from get-login-password is only valid 12h,
        // so this re-authenticates on every run rather than caching it.
        stage('Push to ECR') {
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

                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-ecr-creds'
                    ]]) {
                        sh """
                            aws ecr get-login-password --region ${AWS_REGION} | \
                              docker login --username AWS --password-stdin ${ECR_REGISTRY}
                        """

                        def pushed = 0
                        services.each { svc ->
                            if (svc.build == 'true') {
                                def imageName = "${ECR_REGISTRY}/${PROJECT}-${svc.name}"
                                sh """
                                    docker push ${imageName}:${IMAGE_TAG}
                                    docker push ${imageName}:latest
                                    echo "✅ Pushed ${imageName}:${IMAGE_TAG}"
                                """
                                pushed++
                            }
                        }

                        if (pushed > 0) {
                            env.IMAGES_PUSHED = 'true'
                            echo "📦 ${pushed} image(s) pushed — QA pipeline will be triggered"
                        } else {
                            env.IMAGES_PUSHED = 'false'
                            echo "⏭️  No images pushed — QA pipeline will be skipped"
                        }

                        sh "docker logout ${ECR_REGISTRY}"
                    }

                    echo """
Images pushed to ECR:
  ${ECR_REGISTRY}/${PROJECT}-<service>
  Tag: ${IMAGE_TAG}
"""
                }
            }
        }

        // ── Stage 5: Trigger Automation Pipeline ─────────────────
        // Always triggers regardless of what changed.
        // The automation pipeline handles missing tags by falling back to :latest.
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
                        job: 'amazon-microservices-qa-pipeline',
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
║  Images pushed to: ${ECR_REGISTRY}
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