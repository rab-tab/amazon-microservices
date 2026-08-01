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
        S3_BUCKET      = "amazon-microservices-build-artifacts-978185568053"
        CODEBUILD_PROJECT = "amazon-microservices-docker-build"
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

        // ── Stage 3: Upload Build Artifacts to S3 ─────────────────
        // CodeBuild runs in AWS and can't see this Jenkins workspace directly,
        // so the built jars are handed off via S3. CodeBuild's buildspec pulls
        // them back down before running `docker build`.
        stage('Upload Artifacts to S3') {
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
                        services.each { svc ->
                            if (svc.build == 'true') {
                                def s3Path = "s3://${S3_BUCKET}/builds/${IMAGE_TAG}/${svc.name}/"
                                echo "📤 Uploading ${svc.name} jar to ${s3Path}"
                                sh "aws s3 cp ${svc.name}/target/ ${s3Path} --recursive --exclude '*' --include '*.jar'"
                            }
                        }
                    }
                }
            }
        }

        // ── Stage 4: Build & Push via CodeBuild ───────────────────
        // Triggers one CodeBuild run per changed service, in parallel — CodeBuild
        // runs on its own managed compute, so there's no laptop-memory reason to
        // serialize these the way the old local `docker build` stage had to.
        // Each build's status is polled until it finishes; any failure fails
        // this stage (and the pipeline) so the QA trigger below never fires on
        // a broken image.
        stage('Build & Push via CodeBuild') {
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

                    def toBuild = services.findAll { it.build == 'true' }

                    if (toBuild.isEmpty()) {
                        env.IMAGES_PUSHED = 'false'
                        echo "⏭️  No services changed — CodeBuild will be skipped"
                        return
                    }

                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-ecr-creds'
                    ]]) {
                        def buildTasks = [:]

                        toBuild.each { svc ->
                            def s = svc.name  // capture for closure
                            buildTasks[s] = {
                                def s3Uri = "s3://${S3_BUCKET}/builds/${IMAGE_TAG}/${s}/"
                                echo "🚀 Starting CodeBuild for ${s}"

                                def buildId = sh(
                                    script: """
                                        aws codebuild start-build \
                                          --project-name ${CODEBUILD_PROJECT} \
                                          --environment-variables-override \
                                            name=SERVICE_NAME,value=${s},type=PLAINTEXT \
                                            name=IMAGE_TAG,value=${IMAGE_TAG},type=PLAINTEXT \
                                            name=ECR_REGISTRY,value=${ECR_REGISTRY},type=PLAINTEXT \
                                            name=ARTIFACT_S3_URI,value=${s3Uri},type=PLAINTEXT \
                                          --query 'build.id' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                echo "⏳ ${s}: CodeBuild running (${buildId})"

                                def status = 'IN_PROGRESS'
                                def elapsed = 0
                                def timeoutSecs = 900  // 15 min per service is generous headroom

                                while (status == 'IN_PROGRESS' && elapsed < timeoutSecs) {
                                    sleep(15)
                                    elapsed += 15
                                    status = sh(
                                        script: "aws codebuild batch-get-builds --ids ${buildId} --query 'builds[0].buildStatus' --output text",
                                        returnStdout: true
                                    ).trim()
                                    echo "  ${s}: ${status} (${elapsed}s)"
                                }

                                if (status != 'SUCCEEDED') {
                                    error("❌ CodeBuild for ${s} ended with status: ${status} (build id: ${buildId})")
                                }
                                echo "✅ ${s}: CodeBuild succeeded"
                            }
                        }

                        parallel buildTasks
                    }

                    env.IMAGES_PUSHED = 'true'
                    echo """
Images pushed to ECR via CodeBuild:
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