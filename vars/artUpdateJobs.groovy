def call(String gitUrl=None, String gitBranch="master") {
  pipeline {
    options {
      timestamps()
    }
    agent {
      kubernetes {
        cloud "openshift"
        label "art-jenkins-agent-${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}"
        serviceAccount "art-jenkins-slave"
        defaultContainer 'jnlp'
        yaml """
        apiVersion: v1
        kind: Pod
        metadata:
          labels:
            app: "jenkins"
        spec:
            containers:
            - name: jnlp
              image: "docker-registry.default.svc:5000/art-jenkins/art-jenkins-slave:latest"
              imagePullPolicy: Always
              tty: true
              resources:
                requests:
                  memory: 378Mi
                  cpu: 200m
                limits:
                  memory: 768Mi
                  cpu: 500m
        """
      }
    }
    stages {
      stage("Checkout") {
        steps {
          script {
            def scmVars = checkout(
              scm: [$class: 'GitSCM',
                branches: [[name: gitBranch]],
                userRemoteConfigs: [
                  [
                    name: 'origin',
                    url: gitUrl,
                    refspec: '+refs/heads/*:refs/remotes/origin/* +refs/tags/*:refs/remotes/origin/tags/* +refs/pull/*/head:refs/remotes/origin/pull/*/head',
                  ],
                ],
                extensions: [[$class: 'CleanBeforeCheckout']],
              ],
              poll: false,
              changelog: true,
            )
            env.GIT_COMMIT = scmVars.GIT_COMMIT
            // setting build display name
            def prefix = 'origin/'
            def branch = scmVars.GIT_BRANCH.startsWith(prefix) ? scmVars.GIT_BRANCH.substring(prefix.size())
              : scmVars.GIT_BRANCH // origin/pull/1234/head -> pull/1234/head, origin/master -> master
            env.GIT_BRANCH = branch
            echo "Build on branch=${env.GIT_BRANCH}, commit=${env.GIT_COMMIT}"
            currentBuild.displayName = "${env.GIT_BRANCH}: ${env.GIT_COMMIT.substring(0, 7)}"
          }
        }
      }
      stage("Update Jobs") {
        steps {
          script {
            def changedFiles = getChangedFiles(this)
            openshift.withCluster() {
              for (changedFile in changedFiles) {
                  (editType, path) = changedFile
                  echo "Processing change: ${editType} ${path}"
                  processFileChange(this, editType, path)
              }
            }
          }
        }
      }
    }
  }
}

@NonCPS 
def getChangedFiles(script) {
  def changeLogSets = script.currentBuild.changeSets
  def changedFiles = []
  for (int i = 0; i < changeLogSets.size(); i++) {
    def entries = changeLogSets[i].items
    for (int j = 0; j < entries.length; j++) {
      def entry = entries[j]
      echo "${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
      def files = new ArrayList(entry.affectedFiles)
      for (int k = 0; k < files.size(); k++) {
        def file = files[k]
        changedFiles.add([file.editType.name, file.path])
      }
    }
  }
  return changedFiles
}

def processFileChange(script, String editType, String path) {
  if (!(editType in ["add", "edit"])) {
    echo "Ignoring $editType $path"
    return // ignore deleted files
  }
  if (path.startsWith("openshift-pipelines/jobs/") || path.startsWith("openshift-pipelines/infra/")) {
    // jobs update
    echo "Applying API resource $path..."
    script.openshift.apply("-f", path)
  }
  if (path.startsWith("openshift-pipelines/images/") && path.endsWith("Dockerfile")) {
    // images update
    def imageName = new File(path).name
    imageName = imageName.substring(0, imageName.lastIndexOf('.'))
    echo "Building image $imageName..."
    def build = artHelper.build(script, imageName)
    artHelper.wait(script, build)
  }
}
