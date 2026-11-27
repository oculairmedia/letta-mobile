#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
base=https://maven-central.storage-download.googleapis.com/maven2
fetch() { curl --retry 3 -fsSL "$base/$1" -o "$tmp/$2"; echo "$3  $tmp/$2" | sha256sum -c -; }
fetch org/automerge/automerge/0.0.9/automerge-0.0.9.jar original.jar 7629272a27287b675cd26209cb53ec9aaf4b0e3ecb464fb4df7e7a08b9e24c8e
fetch org/automerge/automerge/0.0.9/automerge-0.0.9-sources.jar sources.jar a414d059ff2ddb9df8ebaf73693c2444b0b11e2693729986dacfb9dcf6f4e668
fetch org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar slf4j.jar 0818930dc8d7debb403204611691da58e49d42c50b6ffcfdce02dadb7c3c2b6c
cd "$tmp"
/usr/lib/jvm/java-21-openjdk-amd64/bin/jar xf sources.jar org/automerge/repo/RepoRuntime.java
patch -p1 < "$here/RepoRuntime.java.patch"
mkdir classes
/usr/lib/jvm/java-21-openjdk-amd64/bin/javac --release 8 -classpath original.jar:slf4j.jar -d classes org/automerge/repo/RepoRuntime.java
cp original.jar patched.jar
cd classes
find org/automerge/repo -name 'RepoRuntime*.class' -print0 | sort -z | xargs -0 /usr/lib/jvm/java-21-openjdk-amd64/bin/jar uf "$tmp/patched.jar"
cp "$tmp/patched.jar" "$here/../maven/org/automerge/automerge/0.0.9-letta1/automerge-0.0.9-letta1.jar"
