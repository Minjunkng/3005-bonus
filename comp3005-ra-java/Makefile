JAVA_RUN = sh tools/java.sh

.PHONY: all test clean
all: build/RA.class build/GenerateData.class

build/RA.class: src/RA.java tools/java.sh
	mkdir -p build
	@if command -v javac >/dev/null 2>&1; then \
		javac -encoding UTF-8 -d build src/RA.java; \
	else \
		$(JAVA_RUN) -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build src/RA.java; \
	fi

build/GenerateData.class: src/GenerateData.java tools/java.sh
	mkdir -p build
	@if command -v javac >/dev/null 2>&1; then \
		javac -encoding UTF-8 -d build src/GenerateData.java; \
	else \
		$(JAVA_RUN) -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build src/GenerateData.java; \
	fi

test: all
	python3 -m unittest discover -s tests -v

clean:
	rm -rf build
