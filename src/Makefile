# Compilatorul si executabilul Java
JAVAC = javac
JAVA = java

CLASSPATH = .:lib/*

MAIN_CLASS = Main

build:
	$(JAVAC) -cp "$(CLASSPATH)" *.java

run:
	$(JAVA) -cp "$(CLASSPATH)" $(MAIN_CLASS) $(ARGS)

clean:
	find . -name '*.class' -type f -print0 | xargs -0 rm -f