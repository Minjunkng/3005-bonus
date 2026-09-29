#!/bin/sh
# Use an ordinary Java installation when available. Some sandbox images ship
# the compiler module without javac and omit libjli.so from the loader path.
if java -version >/dev/null 2>&1; then
    exec java "$@"
fi
for jdk_root in "${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk-amd64; do
    if [ -n "$jdk_root" ] && [ -f "$jdk_root/lib/libjli.so" ]; then
        LD_LIBRARY_PATH="$jdk_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        export LD_LIBRARY_PATH
        exec "$jdk_root/bin/java" "$@"
    fi
done
printf 'Java runtime not found. Install a Java 17+ JDK.\n' >&2
exit 127
