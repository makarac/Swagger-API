#!/usr/bin/env groovy

def sdlcEnv=(env.sdlcEnv) ? env.sdlcEnv : "prod"
def jenkinsInstance=env.JENKINS_URL.replaceFirst(/https?:\/\//,"").split(/\./)[0]
def kubernetesCloud=( sdlcEnv == "prod" ) ? "prod-ucp-ca--prod-whs-imagebuildpipeline-ns" : "dev-ucp-ca--dev-whs-imagebuildpipeline-ns"
def podYamlFile="k8s-deployment-automation/" + sdlcEnv + "-k8s-deployment-pod.yaml"
def podLabel=sdlcEnv + "-" + jenkinsInstance + "-k8s-deployment-pod"
def clusterChoices=(env.sdlcEnv == "prod") ? [ 'dev-ucp-ca','prod-ucp-ca'] : ['dev-ucp-ca','prod-ucp-ca']
def scmRepoUrlProxy="ssh://git@bitbucket.sunlifecorp.com/eadp/eadp-light-proxy.git"
def scmProxyBranch=['0.1.0','1.0.0','1.0.1']
def digestOverlayDir="__image-digest-overlay__";
def imageName;
def digest;
def overlayDir="";
def skipPermissionCheck=(env.skipPermissionCheck == "true") ? "--skipPermissionCheck" : "";

// Extracting credentails here to prevent masking of user name in shell output
def getUserPassword( credentialId ) {
    if( credentialId == null || credentialId == '' ) {
      return [ user: "changeme", password: "changeme" ];
    } 
    
    withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'PASSWD', usernameVariable: 'USRNAME')]) {
        return [user: USRNAME, password: PASSWD ];
    }
}

pipeline {

	parameters {
    string(name: 'scmRepoUrl', defaultValue: 'ssh://git@bitbucket.sunlifecorp.com/eadp/eadp-reference-api.git', description: 'A URL pointing to a Bitbucket repository that contains Kubernetes Kustomize deployment files and openapi specification . The protocol must be SSH.')
    string(name: 'scmBranch', defaultValue: 'develop' , description: 'SCM branch, tag or commit')
    choice(name: 'scmProxyBranch', choices: scmProxyBranch, description: 'Proxy Release Branch')
    credentials( name: 'scmCredential', description: 'SCM credential', required: true, defaultValue: 'e1b989a8-616a-4b20-8b64-47c409cbccb6')
    credentials(credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl',name: 'credential', description: 'An organization admin or a member of a team that is granted the deploy role. Jenkins credential type must be "Username with password".', required: true,  defaultValue: 'eadp')
    choice(name: 'cluster', choices: clusterChoices, description: 'Kubernetes cluster.')
    //string(name: 'version', description: 'an integer between 1 and 99', defaultValue: '')
    //string(name: 'releaseEnvironment', description: 'green or blue', defaultValue: '')
    string(name: 'kustomizeOverlayDir', defaultValue: 'src/main/kubernetes/overlays/dev', description: 'Kubernetes kustomize overlay directory.')
    booleanParam(name: 'dryRun', defaultValue: false, description: 'Dry run.')
    booleanParam(name: 'rolloutStatus', defaultValue: true, description: 'Wait for deployment,statefulset, and daemonset rollout to complete.')
    string( name: 'imageDigest', defaultValue: '', description: 
        'Optional. Digest of the Docker image to be deployed. Overrides image tag in container specifications.\n' + 
        'The image digest can be found in digest.txt file in the Docker image build job build artifacts.\n' +
        'Example: dtestage-dtr.sunlifecorp.com/myorg/myimage@sha256:e7cefa4611d1fc77b147d044007d9c3ccde1d515b1691eebdb785a563ed8e846\n' +
        'Can be used to deploy into NON-PRODUCTION clusters ONLY!\n' +
        'CAUTION: make sure you understand side effects of this feature, refer to the deployment pipeline user documentation for details.'
         )
    //string(name: 'apiTarget', defaultValue: 'kubernetes' , description: 'kubernetes or lambda or external')
    //string(name: 'namespace', defaultValue: 'namespace' , description: 'REQUIRED ONLY IF apiTarget != kubernetes')
    //string(name: 'openAPISpecDir', defaultValue: 'openAPISpecDir', description: 'points to the directory within the scmRepoUrl/branch that contains the OpenAPI spec openapi.yaml')
  }

agent {
      kubernetes {
        cloud kubernetesCloud
        label podLabel
        yamlFile podYamlFile
      }
  }
	options {
    	timestamps()
	}

  stages {
    stage ('Checkout Light Proxy Repo From SCM') {

      when {
        expression {
          return scmRepoUrlProxy && scmRepoUrlProxy.trim().length() > 0 && params.scmProxyBranch && params.scmProxyBranch.trim().length() > 0
        }
      }
      steps {
        container('k8s-deployment-automation') {

          checkout scm: [ $class: 'GitSCM',
                      branches: [[name: "tags/"+params.scmProxyBranch ]],
                      extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'proxy']],
                      userRemoteConfigs: [[credentialsId: params.scmCredential,
                      url: scmRepoUrlProxy ]]]
        }
      }
    }

    stage ('Checkout Application Repo From SCM') {

      when {
        expression {
          return params.scmRepoUrl && params.scmRepoUrl.trim().length() > 0 && params.scmBranch && params.scmBranch.trim().length() > 0
        }
      }
      steps {
        container('k8s-deployment-automation') {

          checkout scm: [ $class: 'GitSCM',
                      branches: [[name: params.scmBranch ]],
                      userRemoteConfigs: [[credentialsId: params.scmCredential,
                      url: params.scmRepoUrl ]]]
          script {
            baseDir=params.kustomizeOverlayDir.minus(~"(/overlays/.*)")
            appOverlayEnv=params.kustomizeOverlayDir.minus(baseDir).minus(~"(/overlays/)")
            sh "cp -r proxy/k8s/* ${baseDir}"
            sh "value_file=`ls -1 ${params.kustomizeOverlayDir}/proxy-config/values.y*ml`; cat ${baseDir}/overlays/_${appOverlayEnv}proxy_/config/values.yml >> \${value_file}"
          }
        }
      }
    }

    stage( 'Add Image Digest Kustomize Overlay' ) {
      when {
        expression {
          return params.scmRepoUrl && params.scmRepoUrl.trim().length() > 0
        }
        
        expression {
          return params.scmBranch && params.scmBranch.trim().length() > 0
        }
        
        expression {
          return params.imageDigest && params.imageDigest.trim().length() > 0
        }
      }
      steps {
      
        container('k8s-deployment-automation') {
        
          script {
            if( params.cluster ==~ /.*prod.*/ ) {
              error('Deployment to production clusters using image digests is not supported!' + 
                  ' Refer to the deployment pipeline user documentation for details.');
            }
            
            ( imageName, digest ) = params.imageDigest.split("@");
          }
          
          sh """set +x; [ -d ${digestOverlayDir} ] || mkdir ${digestOverlayDir} && cd ${digestOverlayDir} && cat << EOF > kustomization.yaml
            apiVersion: kustomize.config.k8s.io/v1beta1
            kind: Kustomization

            bases:
            - ../${baseDir}/overlays/_${appOverlayEnv}proxy_
            images:
            - name: ${imageName}
              digest: ${digest}
              """

					echo "Additional Kustomize overlay to roll out the image digest"
          sh "cat ${digestOverlayDir}/kustomization.yaml"
        }
        script {
          overlayDir=digestOverlayDir;
        }
      }
    }
    
    stage ('Prepare Kustomization') {
      steps {
       container('k8s-deployment-automation') {
         script{
           sh "chmod +x ${baseDir}/template.sh"
           sh "set +x; \
               echo '' >> ${params.kustomizeOverlayDir}/vars; \
               echo PROXY_IMAGE_VERSION=${params.scmProxyBranch} >> ${params.kustomizeOverlayDir}/vars; \
               echo KUSTOMIZATION_DIR=${baseDir} >> ${params.kustomizeOverlayDir}/vars; \
           	   source ${baseDir}/overlays/_${appOverlayEnv}proxy_/vars; \
               dos2unix ${params.kustomizeOverlayDir}/vars; \
               source ${baseDir}/template.sh ${params.kustomizeOverlayDir}/vars;"
           sh "cat ${baseDir}/*.y*ml;cat ${params.kustomizeOverlayDir}/vars"
         }
       }
      }
     }

    stage ( 'Deploy Kustomization Overlay'){
      when {
        expression {
          return params.scmRepoUrl && params.scmRepoUrl.trim().length() > 0
        }
        
        expression {
          return params.scmBranch && params.scmBranch.trim().length() > 0
        }

        expression {
          return params.kustomizeOverlayDir && params.kustomizeOverlayDir.trim().length() > 0
        }
      }

      steps {
        script {
          if( overlayDir == "" )
            overlayDir=params.kustomizeOverlayDir;
            
          if( params.credential == null || params.credential.trim().size() == 0 ) {
            error('Deployment credential is not set');
          }
        }

        container('k8s-deployment-automation') {
          script{    
            def userPassword=getUserPassword( params.credential );
            sh "set +x; /scripts/k8s-kustomize-deploy.sh \
                        --cluster ${params.cluster} \
                        --kustomizeOverlayDir \"${baseDir}\" \
                        --dryRun ${params.dryRun} \
                        --rolloutStatus ${params.rolloutStatus} \
                        --user \"${userPassword.user}\" \
                        --password \"${userPassword.password}\" \
                        ${skipPermissionCheck}"
           } //script 
        } //container
      } //steps
    } // stage
  } // stages
} //pipeline
