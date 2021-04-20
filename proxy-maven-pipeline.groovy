#!/usr/bin/env groovy
def scmRepoUrl="ssh://git@bitbucket.sunlifecorp.com/eadp/eadp-light-proxy.git"
def scmCredential="e1b989a8-616a-4b20-8b64-47c409cbccb6"
def dtrLoginCredential="eadp"
def dockerfileName="Dockerfile"
pipeline {
	parameters {
    string(name: 'scmBranch', defaultValue: 'develop' , description: 'SCM branch, tag or commit')
    string(name: 'tag', defaultValue: 'latest' , description: 'docker image tag')	
  }
agent { label 'winbuild' }
  stages {
    stage ('SCM') {
      steps {
	   checkout scm: [ $class: 'GitSCM',branches: [[name: params.scmBranch ]],userRemoteConfigs: [[credentialsId: scmCredential,url: scmRepoUrl ]]]
      }
	}
    stage ('Build && SonarQube analysis') {
      steps {
		withSonarQubeEnv('SonarQube') {
		 withMaven(globalMavenSettingsConfig: '8d2a7047-31b1-47a8-8788-be4b89b71125', jdk: 'JDK 11', maven: 'Maven 3.3.9') {
		  withEnv(["client.oauth.token.key.proxyHost=proxy-mwg-http.ca.sunlife","client.oauth.token.key.proxyPort=8443"]) {
           sh '${MAVEN_HOME}/bin/mvn -f ./pom.xml -s ./settings.xml \
               --batch-mode -V -U -e deploy -Dsurefire.useFile=true verify sonar:sonar'
		  }
		}
       }
      }
    }
    stage("Quality Gate"){
      steps {
	   script {
		timeout(time: 5, unit: 'MINUTES') {
              def qg = waitForQualityGate()
              if (qg.status != 'OK') {
                  error "Pipeline aborted due to quality gate failure: ${qg.status}"
         }
        }
	   }
      }
    }
    stage ('Docker Image Build') {
      steps {
	   	build(
		  job: 'eadp-ligth-proxy-docker',
		  parameters: [
			[
			  $class: 'StringParameterValue',
			  name: 'scmRepoUrl',
			  value: "${scmRepoUrl}"
			],
			[
			  $class: 'StringParameterValue',
			  name: 'scmBranch',
			  value: "${params.scmBranch}"
			],
			[
			  $class: 'StringParameterValue',
			  name: 'scmCredential',
			  value: "${scmCredential}"
			],
			[
			  $class: 'StringParameterValue',
			  name: 'dtrLoginCredential',
			  value: "${dtrLoginCredential}"
			],
			[
			  $class: 'StringParameterValue',
			  name: 'dockerfileName',
			  value: "${dockerfileName}"
			],
			[
			  $class: 'StringParameterValue',
			  name: 'imageTag',
			  value: "${params.tag}"
			]
		  ]
		)
      }
	}
  }
}
