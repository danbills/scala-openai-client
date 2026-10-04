// Releases are cut locally, not from CI, so there is no sbt-ci-release here —
// only the two pieces it would have bundled. Uploading to the Central Portal
// is built into sbt 2 itself (`sonaUpload` / `sonaRelease`).
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")  // version from the git tag
addSbtPlugin("com.github.sbt" % "sbt-pgp"    % "2.3.1")  // publishSigned