def build(script, String bcname, String... args) {
  def bc = script.openshift.selector("bc", bcname)
  return build(script, bc, args)
}

def build(script, selector, String... args) {
  return selector.startBuild(args as String[]).narrow("build")
}

def waitForStart(script, build, int timeout=5) {
  script.echo "Waiting for ${build.name()} to start..."
  script.timeout(time: timeout) {
    build.watch {
      return !(it.object().status.phase in ["New", "Pending", "Unknown"])
    }
  }
  return build
}

def wait(script, build, int timeout=60) {
  waitForStart(script, build)
  def buildobj = build.object()
  def buildurl = buildobj.metadata?.annotations?.get('openshift.io/jenkins-build-uri')
  if (buildurl) {
    script.echo "Details: ${buildurl}"
  }
  if (buildobj.spec.strategy.type == "JenkinsPipeline") {
    script.echo "Waiting for ${build.name()} to complete..."
    build.logs("--tail=1")
    script.timeout(time: timeout) {
      build.watch {
        it.object().status.phase != "Running"
      }
    }
  } else {
    script.echo "Following build logs..."
    script.timeout(time: timeout) {
      while (build.object().status.phase == "Running") {
        build.logs("--tail=1", "--timestamps=true", "-f")
      }
    }
  }
  buildobj = build.object()
  if (buildobj.status.phase != "Complete") {
    script.error "Build ${buildobj.metadata.name} ${buildobj.status.phase}"
  }
  return build
}
