# Run after downloading (data/downloadInputs.sh) to generate
# per-language data files from enwiktionary.
ICU4J=/usr/share/java/icu4j-49.1.jar
test -r "$ICU4J" || ICU4J=/usr/share/icu4j-55/lib/icu4j.jar
XERCES=/usr/share/java/xercesImpl.jar
test -r "$XERCES" || XERCES=/usr/share/xerces-2/lib/xercesImpl.jar
JAVA=/usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
test -x "$JAVA" || JAVA=java
"$JAVA" -classpath src:../Util/src/:../Dictionary/src/:"$ICU4J":"$XERCES" com.hughes.android.dictionary.engine.WiktionarySplitter "$@"
