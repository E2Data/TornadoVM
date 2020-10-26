pipeline {
    agent any
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
    }
    environment {
        JAVA_HOME="/opt/jenkins/openjdk1.8.0_262-jvmci-20.2-b03"
        TORNADO_ROOT="/var/lib/jenkins/workspace/Tornado-pipeline"
        PATH="/var/lib/jenkins/workspace/Slambench/slambench-tornado-refactor/bin:/var/lib/jenkins/workspace/Tornado-pipeline/bin/bin:$PATH"    
        TORNADO_SDK="/var/lib/jenkins/workspace/Tornado-pipeline/bin/sdk" 
        CMAKE_ROOT="/opt/jenkins/cmake-3.10.2-Linux-x86_64"
        KFUSION_ROOT="/var/lib/jenkins/workspace/Slambench/slambench-tornado-refactor"
    }
    stages {
        stage('Checkout Current Branch') {
            steps {
                step([$class: 'WsCleanup'])
                checkout scm
                sh 'git checkout master'
                checkout([$class: 'GitSCM', branches: [[name: '**']], doGenerateSubmoduleConfigurations: false, extensions:[[$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: '9bca499b-bd08-4fb2-9762-12105b44890e', url: 'https://github.com/beehive-lab/TornadoVM-Internal.git']]])
           }
        }
        stage('[PTX] Build with JDK-8') {
            steps {
                sh 'make BACKEND=ptx'
            }
        }
        stage('[OpenCL + PTX] Build with JDK-8') {
            steps {
                sh 'make BACKEND=opencl,ptx'
            }
        }
        stage('[OpenCL] Build with JDK-8') {
            steps {
                sh 'make BACKEND=opencl'
                sh 'bash bin/bin/tornadoLocalInstallMaven'
            }
        }
        stage("Unit Tests") {
            parallel {
                stage('GPU: Nvidia GeForce GTX 1060') {
                    steps {
                        timeout(time: 5, unit: 'MINUTES') {
                            sh 'tornado-test.py --verbose -J"-Dtornado.unittests.device=0:1"'
                            sh 'tornado-test.py -V  -J"-Dtornado.unittests.device=0:1" -J"-Dtornado.heap.allocation=1MB" uk.ac.manchester.tornado.unittests.fails.HeapFail#test03'
                            sh 'test-native.sh'
                        }
                    }
                }
                stage('CPU: Intel Xeon E5-2620') {
                    steps {
                        timeout(time: 5, unit: 'MINUTES') {
                            sh 'tornado-test.py --verbose -J"-Dtornado.unittests.device=0:0"'
                            sh 'tornado-test.py -V  -J"-Dtornado.unittests.device=0:0" -J"-Dtornado.heap.allocation=1MB" uk.ac.manchester.tornado.unittests.fails.HeapFail#test03'
                            sh 'test-native.sh'
                        }
                    }
                }
            }
        }
        stage('Test GPU Reductions') {
        	steps {
				timeout(time: 5, unit: 'MINUTES') {
               		sh 'tornado-test.py -V -J"-Ds0.t0.device=0:1 -Ds0.t1.device=0:1" uk.ac.manchester.tornado.unittests.reductions.TestReductionsFloats'
               		sh 'tornado-test.py -V -J"-Ds0.t0.device=0:1 -Ds0.t1.device=0:1" uk.ac.manchester.tornado.unittests.reductions.TestReductionsDoubles'
               		sh 'tornado-test.py -V -J"-Ds0.t0.device=0:1 -Ds0.t1.device=0:1" uk.ac.manchester.tornado.unittests.reductions.TestReductionsIntegers'
               		sh 'tornado-test.py -V -J"-Ds0.t0.device=0:1 -Ds0.t1.device=0:1" uk.ac.manchester.tornado.unittests.reductions.TestReductionsFloats'
            	}
			}
        }       
		stage('Benchmarks') {
        	steps {
				timeout(time: 10, unit: 'MINUTES') {
                	sh 'python assembly/src/bin/tornado-benchmarks.py --printBenchmarks '
                	sh 'python assembly/src/bin/tornado-benchmarks.py --medium --skipSequential --iterations 5 '
            	}
			}
        }
         stage('Clone & Build KFusion') {
        	steps {
				timeout(time: 5, unit: 'MINUTES') {
                	sh 'cd /var/lib/jenkins/workspace/Slambench/slambench-tornado-refactor && git fetch && git pull origin master && mvn clean install -DskipTests'
            	}
			}
        }
        stage('Run KFusion') {
        	steps {
				timeout(time: 5, unit: 'MINUTES') {
                	sh 'cd /var/lib/jenkins/workspace/Slambench/slambench-tornado-refactor && kfusion kfusion.tornado.Benchmark /var/lib/jenkins/workspace/Slambench/slambench-tornado-refactor/conf/traj2.settings'
            	}
			}
        }
    }
    post {
        success {
            slackSend color: '#00CC00', message: "SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})"
            deleteDir()
        }   
       failure {
            slackSend color: '#CC0000', message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})"
        }
    }
}
